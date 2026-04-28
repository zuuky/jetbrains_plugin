from __future__ import annotations

import re
import uuid
from collections import Counter
from dataclasses import dataclass, field, replace
from typing import Any, Literal, Optional

import regex
import requests
from pydantic import BaseModel

from sweep_autocomplete.autocomplete.next_edit_autocomplete_retrieval import (
    find_best_matching_block,
)
from sweep_autocomplete.autocomplete.next_edit_autocomplete_service import (
    next_edit_autocomplete_service,
)
from sweep_autocomplete.autocomplete.next_edit_autocomplete_utils import (
    AUTOCOMPLETE_OUTPUT_MAX_TOKENS,
    PromptTooLongError,
    adjust_cursor_position_from_utf16,
    extract_diff_parts,
    filter_whitespace_only_hunks,
    get_line_number_from_position,
    get_lines_around_cursor,
    is_equal_ignoring_newlines,
    is_large_diff_above_cursor,
    parse_hunk,
    should_disable_for_code_block,
    split_into_diff_hunks,
    split_into_hunks,
    strip_leading_empty_newlines,
    truncate_long_lines,
)
from sweep_autocomplete.autocomplete.llm_local import (
    generate_completion,
    RequestCancelled,
)
from sweep_autocomplete.config import NEXT_EDIT_AUTOCOMPLETE_ENDPOINT
from sweep_autocomplete.dataclasses.file_chunk_data import (
    EditorDiagnostic,
    FileChunkData,
    UserAction,
)
from loguru import logger
from sweep_autocomplete.utils.str_utils import pack_items_for_prompt
from sweep_autocomplete.utils.timer import Timer

NUM_LINES_BEFORE = 2
NUM_LINES_AFTER = 5

CHARS_PER_TOKEN = 3.5


def estimate_token_count(text: str) -> int:
    """Estimate token count using character-based approximation."""
    return int(len(text) / CHARS_PER_TOKEN)


MAX_INPUT_TOKENS_COUNT = (
    8192 * 4
) - 256  # ~8k tokens at 3.5 chars/token, fits in 32k ctx
CHARACTER_BOUND_TO_CHECK_TOKENIZATION = (8192 * 2) - 256  # ~4k tokens
CHARACTER_BOUND_TO_SKIP_TOKENIZATION = (
    8192 * 4
) * 2  # ~16k tokens, skip if clearly too long
MAX_RETRIEVAL_CHUNK_SIZE_LINES = 25
DEBUG = False
# DEBUG = True

MAX_RETRIEVAL_CHUNKS = 3
MAX_TIMEOUT_MS = 2000
NUM_RECENT_ACTIONS_TO_PRESERVE = 20

# source: https://github.com/huggingface/transformers/blob/main/src/transformers/models/qwen2/tokenization_qwen2.py#L39
PRETOKENIZE_REGEX = r"""(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""

pretokenize_regex = regex.compile(PRETOKENIZE_REGEX)


class PromptTruncationRecord(BaseModel):
    """Data container for prompt truncation logic. No S3 saving."""

    autocomplete_id: str = ""
    original_prompt_length: int = 0
    final_prompt_length: int = 0
    max_seq_len: int = 0
    lower_bound: int = 0
    truncation_occurred: bool = False
    truncation_reason: str = ""
    retrieval_results_length: int = 0
    file_chunks_used: int = 0
    file_chunks_available: int = 0
    file_path: str = ""
    cursor_position: float = 0
    initial_file: str = ""
    retrieval_results: str = ""
    recent_changes: str = ""
    prev_section: str = ""
    code_block: str = ""
    suffix: str = ""
    prefix: str = ""
    prefill: str = ""
    file_chunks: list[FileChunkData] = []
    start_line: int = 0
    end_line: int = 0


def find_ast_based_prefill_start(
    code_block: str,
    cursor_position: int,
    file_path: str,
    file_contents: str,
    block_start_index: int,
) -> int | None:
    """
    Find the start of the AST node containing the cursor position and crawl up parent nodes
    to determine the optimal prefill start position. Only use parent nodes if they start
    within 1-2 lines above the cursor position.

    Args:
        code_block: The code block containing the cursor
        cursor_position: The cursor position within the code block (relative to code_block)
        file_path: The file path to determine language
        file_contents: The entire file contents for proper AST parsing
        block_start_index: The absolute position where code_block starts in file_contents

    Returns:
        The byte offset for the prefill start position relative to code_block, or None if AST parsing fails
    """
    return None


recent_edits_format = """The user recently made the following changes:

<recent_changes>
{recent_changes}
</recent_changes>

"""

prompt = """<|file_sep|>{file_path}
{initial_file}{retrieval_results}
{recent_changes}
<|file_sep|>original/{file_path}:{start_line}:{end_line}
{prev_section}
<|file_sep|>current/{file_path}:{start_line}:{end_line}
{code_block}
<|file_sep|>updated/{file_path}:{start_line}:{end_line}"""

diff_format = """<|file_sep|>{file_path}:{start_line}:{end_line}
original:
{old_code}
updated:
{new_code}"""

_session = None  # Add session for connection pooling


def remove_last_line_from_string(s: str) -> str:
    return s[: s.rfind("\n")] if "\n" in s else s


def check_early_return_condition(
    accumulated_response: str,
    prefill: str,
    cleaned_lines: list[str],
    cursor_pos: int,
) -> str | None:
    """
    Check if early return condition is met based on accumulated response.
    This is adapted from the production code.

    Returns:
        The early completion string if early return should happen, None otherwise.
    """
    cleaned_content = "".join(cleaned_lines)
    cleaned_content = cleaned_content.removeprefix(prefill)

    lines = cleaned_content.splitlines(True)
    if not lines:
        # Handle empty content case
        return None
    first_line, *rest = lines
    adjusted_cursor_pos = cursor_pos - len(prefill)
    prefix = first_line[:adjusted_cursor_pos]
    suffix = first_line[adjusted_cursor_pos:]

    if "\n" in accumulated_response:
        first_accumulated_response_line = accumulated_response.splitlines(True)[0]
        remainder = cleaned_content[adjusted_cursor_pos:]
        if (
            first_accumulated_response_line.startswith(prefix)
            and first_accumulated_response_line.endswith(suffix)
            and len(first_accumulated_response_line) > len(first_line)
            and not remainder.strip().startswith(first_line.strip())
        ):
            return first_accumulated_response_line + "".join(rest)
    return None


@dataclass
class AutocompleteResult:
    """Represents the result of an autocomplete operation."""

    start_index: int
    end_index: int
    completion: str
    confidence: float
    autocomplete_id: str
    logprobs: list = None
    finish_reason: Literal["stop", "length", "timeout", "cancelled", None] = None


@dataclass
class AutocompleteMetadata:
    """Structured metadata for autocomplete events.

    This dataclass replaces the previous loose dict usage to make it
    easy to add new fields while keeping backwards compatibility where needed.
    Use the `extra` field for arbitrary key-value data that doesn't yet
    warrant a dedicated top-level attribute.
    """

    exit_reason: str = "unknown"
    reason: Optional[str] = "unknown"
    # Context size metrics for GCP dashboard correlation
    retrieval_chunks_count: int = -1
    retrieval_chunks_char_count: int = -1
    retrieval_chunks_line_count: int = -1
    file_chunks_count: int = -1
    file_chunks_char_count: int = -1
    file_chunks_line_count: int = -1
    is_retrieval_autocomplete: bool = False
    extra: dict[str, Any] = field(default_factory=dict)

    def convert_to_string(self) -> str:
        """Convert all metadata fields to a nicely formatted string.

        Returns:
            A string representation of all key-value pairs in the format:
            "key1=value1 | key2=value2 | ..."
        """
        parts = [
            f"exit_reason={self.exit_reason}",
            f"reason={self.reason}",
            f"retrieval_chunks_count={self.retrieval_chunks_count}",
            f"retrieval_chunks_char_count={self.retrieval_chunks_char_count}",
            f"retrieval_chunks_line_count={self.retrieval_chunks_line_count}",
            f"file_chunks_count={self.file_chunks_count}",
            f"file_chunks_char_count={self.file_chunks_char_count}",
            f"file_chunks_line_count={self.file_chunks_line_count}",
        ]
        for key, value in self.extra.items():
            parts.append(f"{key}={value}")
        return " | ".join(parts)


def get_block_around_cursor_line(
    lines: list[str], cursor_line: int, num_lines_before: int, num_lines_after: int
):
    block_start = max(0, cursor_line - num_lines_before)
    block_end = min(
        len(lines), cursor_line + num_lines_after + 1
    )  # +1 to include the cursor line

    while block_start < block_end and (lines[block_start].strip() == ""):
        block_start += 1
        if block_end < len(lines):
            block_end += 1

    while block_end > block_start and (lines[block_end - 1].strip() == ""):
        block_end -= 1

    current_block = "".join(lines[block_start:block_end])

    prefix_start = max(0, block_start - 10)
    prefix = "".join(lines[prefix_start:block_start])

    suffix_end = min(len(lines), block_end + 10)
    suffix = "".join(lines[block_end:suffix_end])

    if current_block.endswith("\n"):
        current_block = current_block.strip("\n") + "\n"

    return current_block, prefix.strip("\n"), suffix.strip("\n")


def truncate_code_block_by_tokens(
    code_block: str, max_token_limit: int = int(AUTOCOMPLETE_OUTPUT_MAX_TOKENS / 2)
) -> str:
    """
    Truncate a code block to fit within the specified token limit.

    Args:
        code_block: The code block to truncate
        max_token_limit: Maximum number of tokens allowed (default: AUTOCOMPLETE_OUTPUT_MAX_TOKENS / 2)

    Returns:
        The truncated code block
    """
    code_block_lines = code_block.splitlines(True)
    prefilled_code_block = "".join(code_block_lines[:NUM_LINES_BEFORE])
    remaining_code_block = "".join(code_block_lines[NUM_LINES_BEFORE:])
    estimated_tokens = estimate_token_count(remaining_code_block)

    if estimated_tokens > max_token_limit:
        max_chars = int(max_token_limit * CHARS_PER_TOKEN)
        remaining_code_block = remaining_code_block[:max_chars]

        # Truncate to last complete line
        remaining_code_block_lines = remaining_code_block.splitlines(True)
        if remaining_code_block_lines:
            remaining_code_block = "".join(remaining_code_block_lines[:-1])
            code_block = prefilled_code_block + remaining_code_block

    return code_block


def get_block_at_cursor(
    file_contents: str, cursor_position: int
) -> tuple[str, str, str, int]:
    """
    Extract the code block surrounding the cursor position.
    Returns the code block, prefix, suffix, and block start index.
    """
    # Find cursor line number
    lines = file_contents.splitlines(True)
    cursor_line = get_line_number_from_position(file_contents, cursor_position)
    code_block, prefix, suffix = get_block_around_cursor_line(
        lines, cursor_line, NUM_LINES_BEFORE, NUM_LINES_AFTER
    )
    block_start_line = max(0, cursor_line - NUM_LINES_BEFORE)
    block_start_index = sum(len(line) for line in lines[:block_start_line])

    # Apply token-based truncation
    code_block = truncate_code_block_by_tokens(code_block)

    # Print final token count
    line_count = code_block.count("\n") + 1
    logger.debug(f"Line count: {line_count}")

    return code_block, prefix, suffix, block_start_index


def is_pure_insertion_above_cursor(
    cleaned_code_block: str, completion: str, relative_cursor_position: int
) -> bool:
    current_line_index = len(
        cleaned_code_block[:relative_cursor_position].splitlines(True)
    )
    code_block_lines = cleaned_code_block.splitlines(True)
    cursor_line = code_block_lines[current_line_index - 1]

    if cleaned_code_block.strip() == completion.strip():
        return False

    if not cursor_line.strip():
        return False

    prefix_lines = code_block_lines[: current_line_index - 1]
    prefix = "".join(prefix_lines)
    suffix_lines = code_block_lines[current_line_index:]
    suffix = "".join(suffix_lines)

    if completion.startswith(prefix) and completion.endswith(cursor_line + suffix):
        return True

    return False


def format_recent_changes_and_prev_section(
    recent_changes: str, current_section: str
) -> tuple[str, str, list[str]]:
    hunks = split_into_hunks(recent_changes)
    prev_section = current_section.replace("<|cursor|>", "")
    hunks = [hunk for hunk in hunks if len(hunk.strip().splitlines()) > 1]
    # Filter out pure whitespace changes
    hunks = filter_whitespace_only_hunks(hunks)
    prev_sections = []
    if hunks:
        copied_hunks = hunks[::-1].copy()
        # any_reverts_made = False
        for hunk in copied_hunks:
            first_line, *rest = hunk.splitlines(True)
            file_path = first_line.removeprefix("File: ")
            old_code, new_code = extract_diff_parts("".join(rest))
            old_code_with_context, new_code_with_context = extract_diff_parts(
                hunk, num_context_lines=1
            )
            if new_code_with_context.strip() and new_code_with_context in prev_section:
                prev_section = prev_section.replace(
                    new_code_with_context, old_code_with_context, 1
                )
                prev_sections.append(prev_section)
                # any_reverts_made = True
            elif new_code.strip() and new_code in prev_section:
                prev_section = prev_section.replace(new_code, old_code, 1)
                prev_sections.append(prev_section)
                # any_reverts_made = True
            else:
                break
        # if any_reverts_made:
        #     hunks = hunks[:-1]
    result = ""
    for hunk in hunks[-6:]:  # only keep last three recent changes
        first_line, *rest = hunk.splitlines(True)
        file_path = first_line.removeprefix("File: ").rstrip("\n")
        old_code, new_code = extract_diff_parts("".join(rest), num_context_lines=1)
        _, _, start_line, lines = parse_hunk("".join(rest))
        end_line = start_line + len(lines) - 1
        if old_code.strip() or new_code.strip():
            result += (
                diff_format.format(
                    old_code=old_code.strip("\n"),
                    new_code=new_code.strip("\n"),
                    file_path=file_path,
                    start_line=start_line,
                    end_line=end_line,
                )
                + "\n"
            )
    return result.rstrip("\n"), prev_section, prev_sections


def get_latest_user_action_non_cursor_movement(
    recent_user_actions: list[UserAction],
) -> UserAction | None:
    for action in recent_user_actions[::-1]:
        if action.action_type != "CURSOR_MOVEMENT":
            return action
    return None


def get_last_user_action_index_above_cursor(
    recent_user_actions: list[UserAction],
    cursor_position: int,
    file_path: str,
    block_start_index: int,
) -> int:
    """
    Get the last index of a recent_user_action that occurred above the cursor_position in the current file.

    Args:
        recent_user_actions: List of UserAction objects
        cursor_position: Current cursor position in the file
        file_path: Current file path to filter actions for this file

    Returns:
        Index of the last action above cursor, or -1 if none found
    """
    if not recent_user_actions:
        return -1

    # Find actions in the current file that are above the cursor position
    for i in range(
        len(recent_user_actions) - 1,
        max(-1, len(recent_user_actions) - 1 - NUM_RECENT_ACTIONS_TO_PRESERVE),
        -1,
    ):
        action = recent_user_actions[i]
        if (
            action.file_path == file_path
            and action.action_type != "CURSOR_MOVEMENT"
            and action.offset < cursor_position
        ):
            return action.offset - block_start_index

    return -1


def get_ghost_text_with_location(
    completion: str, cleaned_code_block: str, relative_cursor_position: int
) -> str:
    prefix = cleaned_code_block[:relative_cursor_position]
    suffix = cleaned_code_block[relative_cursor_position:]
    if completion.startswith(prefix) and completion.endswith(suffix):
        # Handle empty suffix case: -0 would slice to beginning, so use conditional
        if suffix:
            ghost_text = completion[len(prefix) : -len(suffix)]
        else:
            ghost_text = completion[len(prefix) :]
        if ghost_text:
            return ghost_text
    return ""


def find_ghost_text_non_local(
    completion: str, cleaned_code_block: str, relative_cursor_position: int
) -> tuple[str, int]:
    if len(cleaned_code_block) > len(completion):
        return "", -1

    # Find all valid ghost text positions and prioritize the one with the longest prefix match
    valid_positions = []
    # Check all positions including len(cleaned_code_block) for empty suffix case
    for pos in range(len(cleaned_code_block) + 1):
        ghost_text = get_ghost_text_with_location(completion, cleaned_code_block, pos)
        if ghost_text:
            valid_positions.append((pos, ghost_text))

    if valid_positions:
        # Prioritize the position with the longest prefix (highest position value)
        # This ensures we match the longest common prefix before the insertion
        best_position, best_ghost_text = max(valid_positions, key=lambda x: x[0])
        return best_ghost_text, best_position

    return "", -1


def is_single_line_ghost_text(
    completion: str, cleaned_code_block: str, relative_cursor_position: int
):
    if len(cleaned_code_block) < relative_cursor_position:
        return ""

    prefix = cleaned_code_block[:relative_cursor_position]
    suffix = cleaned_code_block[relative_cursor_position:]
    if completion.startswith(prefix) and completion.endswith(suffix):
        ghost_text = completion[len(prefix) : -len(suffix)]
        if ghost_text and "\n" not in ghost_text:
            return ghost_text
    return ""


def apply_completions_to_code_block(
    completions: list[AutocompleteResult], file_contents: str, cleaned_code_block: str
) -> str:
    """
    Apply all completions to the cleaned_code_block and return the modified code section.

    Args:
        completions: List of AutocompleteResult objects
        file_contents: The original file contents
        cleaned_code_block: The current code block to apply completions to

    Returns:
        The cleaned_code_block with all completions applied
    """
    if not completions:
        return cleaned_code_block

    # Find the start index of the cleaned code block in the file
    cleaned_code_start_index = file_contents.find(cleaned_code_block)
    if cleaned_code_start_index == -1:
        return cleaned_code_block

    # Create a working copy of the cleaned_code_block
    modified_code_block = cleaned_code_block

    # Sort completions by start_index in descending order to avoid offset issues
    sorted_completions = sorted(completions, key=lambda c: c.start_index, reverse=True)

    # Apply each completion to the cleaned_code_block using relative positioning
    for completion in sorted_completions:
        # Convert absolute file positions to relative positions within the code block
        relative_start = completion.start_index - cleaned_code_start_index
        relative_end = completion.end_index - cleaned_code_start_index

        # Check if the completion affects the code block area
        if (
            relative_start >= 0
            and relative_start <= len(cleaned_code_block)
            and relative_end <= len(cleaned_code_block)
        ):
            # Apply the completion to cleaned_code_block
            modified_code_block = (
                modified_code_block[:relative_start]
                + completion.completion
                + modified_code_block[relative_end:]
            )

    return modified_code_block


def select_best_hunk_from_completion(
    completion: str,
    cleaned_code_block: str,
    file_contents: str,
    cursor_position: int,
    autocomplete_id: str,
    logprobs: list = None,
) -> list[AutocompleteResult]:
    """
    Find the best hunk from the completion to suggest as an edit based on cursor position.

    Args:
        completion: The generated completion text
        cleaned_code_block: The original code block without any markers
        file_contents: The entire file contents
        cursor_position: The current cursor position in the file

    Returns:
        AutocompleteResult containing start_offset, end_offset, new_text, confidence, and autocomplete_id
    """
    completion = strip_leading_empty_newlines(completion)
    cleaned_code_block = strip_leading_empty_newlines(cleaned_code_block)

    if completion == cleaned_code_block:
        return []

    block_start_offset = file_contents.find(cleaned_code_block)
    if block_start_offset == -1:
        return []

    # Edge case: check for ghost text immediately:
    relative_cursor_position = cursor_position - block_start_offset
    ghost_text, ghost_text_position = find_ghost_text_non_local(
        completion, cleaned_code_block, relative_cursor_position
    )
    if ghost_text:
        is_insert_next_line = (
            ghost_text_position == relative_cursor_position + 1
            and cleaned_code_block[ghost_text_position - 1] == "\n"
        )
        insertion_starts_with_newline = (
            ghost_text.startswith("\n")
            and ghost_text_position == relative_cursor_position
        )
        if is_insert_next_line or insertion_starts_with_newline:
            return [
                AutocompleteResult(
                    ghost_text_position + block_start_offset,
                    ghost_text_position + block_start_offset,
                    ghost_text,
                    1.0,
                    f"{autocomplete_id}-0",
                )
            ]

        first_line, *rest = ghost_text.splitlines(True)
        remaining_ghost_text = "".join(rest)

        trailing_whitespace_len = len(first_line) - len(first_line.rstrip("\n"))
        if trailing_whitespace_len > 0:
            trailing_whitespace = first_line[-trailing_whitespace_len:]
            remaining_ghost_text = trailing_whitespace + remaining_ghost_text
            first_line = first_line.rstrip("\n")

        if remaining_ghost_text:
            remaining_ghost_text = remaining_ghost_text.rstrip()
            if (
                ghost_text_position < len(cleaned_code_block)
                and cleaned_code_block[ghost_text_position] == "\n"
            ):
                return [
                    AutocompleteResult(
                        ghost_text_position + block_start_offset,
                        ghost_text_position + block_start_offset,
                        first_line,
                        1.0,
                        f"{autocomplete_id}-0",
                    ),
                    AutocompleteResult(
                        ghost_text_position + block_start_offset,
                        ghost_text_position + block_start_offset,
                        remaining_ghost_text,
                        1.0,
                        f"{autocomplete_id}-1",
                    ),
                ]
        else:
            return [
                AutocompleteResult(
                    ghost_text_position + block_start_offset,
                    ghost_text_position + block_start_offset,
                    ghost_text,
                    1.0,
                    f"{autocomplete_id}-0",
                )
            ]

    hunks = split_into_diff_hunks(cleaned_code_block, completion)

    if not hunks:
        return []

    # Process each hunk to get its absolute position in the file
    processed_hunks = []
    original_lines = cleaned_code_block.splitlines(True)

    for input_start, input_lines, output_start, output_lines in hunks:
        # Calculate start offset in the original text (convert 1-based to 0-based)
        start_line_idx = input_start - 1
        start_offset = block_start_offset
        for i in range(start_line_idx):
            if i < len(original_lines):
                start_offset += len(original_lines[i])

        # Calculate end offset
        end_offset = start_offset
        for i in range(len(input_lines)):
            line_idx = start_line_idx + i
            if line_idx < len(original_lines):
                end_offset += len(original_lines[line_idx])

        new_text = "".join(output_lines)

        # Hack for end of file
        if (
            start_offset == len(file_contents)
            and file_contents[start_offset - 1] != "\n"
        ):
            new_text = "\n" + new_text

        if file_contents[start_offset:end_offset] != new_text:
            processed_hunks.append((start_offset, end_offset, new_text))

    # Find hunks before and after the cursor
    hunks_after_cursor = [
        h for h in processed_hunks if h[1] >= cursor_position
    ]  # use end position to determine before or after
    hunks_before_cursor = [h for h in processed_hunks if h[1] < cursor_position]

    if hunks_after_cursor:
        hunks_after_cursor.sort(key=lambda h: h[0])
        first_hunk, *rest_hunks = hunks_after_cursor
        start_offset, end_offset, new_text = first_hunk
        start_line_position = file_contents[:cursor_position].rfind("\n") + 1

        results = []

        should_split = (
            start_line_position == start_offset <= cursor_position < end_offset
            and new_text.count("\n") > 0
        )

        if should_split:
            # assume it happens in the first line
            first_line, *rest = new_text.splitlines(True)
            remaining_new_text = "".join(rest)

            # Find the end of the first line in the original text
            original_text_section = file_contents[cursor_position:end_offset]
            first_newline_pos = original_text_section.find("\n")
            if first_newline_pos != -1:
                first_line_end = (
                    cursor_position + first_newline_pos + 1
                )  # +1 to include the newline
            else:
                first_line_end = end_offset  # No newline found, use end_offset

            # Check if the first line matches the current cursor line
            end_line_position = file_contents.find("\n", cursor_position)
            if end_line_position == -1:
                end_line_position = len(file_contents)
            current_cursor_line_contents = file_contents[
                start_line_position:end_line_position
            ]
            if not first_line.startswith(current_cursor_line_contents):
                should_split = False

            if should_split:
                results.append(
                    AutocompleteResult(
                        start_offset,
                        first_line_end,
                        first_line,
                        1.0,
                        f"{autocomplete_id}-0",
                        logprobs,
                    )
                )
                if (
                    remaining_new_text
                ):  # Only add second result if there's remaining text
                    results.append(
                        AutocompleteResult(
                            first_line_end,
                            end_offset,
                            remaining_new_text,
                            1.0,
                            f"{autocomplete_id}-1",
                            logprobs,
                        )
                    )
            else:
                results.append(
                    AutocompleteResult(
                        start_offset,
                        end_offset,
                        new_text,
                        1.0,
                        f"{autocomplete_id}-0",
                        logprobs,
                    )
                )
        else:
            results.append(
                AutocompleteResult(
                    start_offset,
                    end_offset,
                    new_text,
                    1.0,
                    f"{autocomplete_id}-0",
                    logprobs,
                )
            )
        max_id = len(rest_hunks)
        results.extend(
            [
                AutocompleteResult(
                    start_offset,
                    end_offset,
                    new_text,
                    1.0,
                    f"{autocomplete_id}-{max_id + i}",
                    logprobs,
                )
                for i, (start_offset, end_offset, new_text) in enumerate(rest_hunks)
            ]
        )
        return results

    # Otherwise, handle hunks before the cursor
    # Fuse hunks that are within 2 lines of each other
    results = []
    if hunks_before_cursor:
        # Sort hunks by start offset to process them in order
        hunks_before_cursor.sort(key=lambda h: h[0])

        # Group hunks that are within 2 lines of each other
        fused_groups = []
        current_group = [hunks_before_cursor[0]]

        for i in range(1, len(hunks_before_cursor)):
            prev_hunk = current_group[-1]
            curr_hunk = hunks_before_cursor[i]

            # Get line numbers for the end of previous hunk and start of current hunk
            prev_end_line = get_line_number_from_position(file_contents, prev_hunk[1])
            curr_start_line = get_line_number_from_position(file_contents, curr_hunk[0])

            # If hunks are within 2 lines of each other, add to current group
            if curr_start_line - prev_end_line <= 2:
                current_group.append(curr_hunk)
            else:
                # Start a new group
                fused_groups.append(current_group)
                current_group = [curr_hunk]

        # Don't forget the last group
        fused_groups.append(current_group)

        # Create AutocompleteResult for each fused group
        for group_idx, group in enumerate(fused_groups):
            # Use the first hunk's start and the last hunk's end
            first_start_offset = group[0][0]
            last_end_offset = group[-1][1]

            # Reconstruct the text by applying all hunks in sequence
            combined_text_parts = []
            current_offset = first_start_offset

            for start_offset, end_offset, new_text in group:
                # Add any unchanged text between hunks
                if current_offset < start_offset:
                    combined_text_parts.append(
                        file_contents[current_offset:start_offset]
                    )
                # Add the new text from this hunk
                combined_text_parts.append(new_text)
                current_offset = end_offset

            combined_new_text = "".join(combined_text_parts)

            results.append(
                AutocompleteResult(
                    first_start_offset,
                    last_end_offset,
                    combined_new_text,
                    1.0,
                    f"{autocomplete_id}-{group_idx}",
                    logprobs,
                )
            )

        # sort by proximity to cursor
        results.sort(key=lambda x: abs(x.start_index - cursor_position))

        return results

    return []


def fetch_next_edits_http(
    formatted_prompt: str,
    stop: list,
    cleaned_code_block: str,
    file_contents: str,
    cursor_position: int,
    prefix: str = "",
    prefill: str = "",
    force_ghost_text: bool = False,
    relative_cursor_line: int = 0,
) -> (
    tuple[str, int, list[Any], str]
    | tuple[str | Any, int, list[Any] | Any, Any | None]
    | tuple[str, int, list[Any] | Any, Any | None]
):
    """Use HTTP streaming to fetch next edits from NEXT_EDIT_AUTOCOMPLETE_ENDPOINT."""
    session = get_session()

    request_data = {
        "prompt": formatted_prompt,
        "stop": stop,
        "max_tokens": AUTOCOMPLETE_OUTPUT_MAX_TOKENS,
        "temperature": 0.0,
        "prefix": prefix,
    }

    headers = {"Content-Type": "application/json"}

    active_endpoint = next_edit_autocomplete_service.active_endpoint
    if not active_endpoint:
        raise Exception("Autocomplete service not available")

    # Use /v1/completions endpoint (OpenAI-compatible)
    completions_endpoint = active_endpoint.rstrip("/") + "/v1/completions"

    try:
        response = session.post(
            completions_endpoint,
            json=request_data,
            headers=headers,
            timeout=5,
            stream=False,
        )
        response.raise_for_status()

        accumulated_response = ""
        telemetry = {}
        logprobs = []
        finish_reason = None

        # Parse the SGLang completions API response (OpenAI format)
        obj = response.json()

        # Log the full response for debugging
        logger.info(f"SGLang response: {obj}")

        # SGLang returns OpenAI-compatible format: {"choices": [{"text": "...", "finish_reason": "..."}], ...}
        if "choices" in obj and len(obj["choices"]) > 0:
            choice = obj["choices"][0]
            accumulated_response = choice.get("text", "")
            finish_reason = choice.get("finish_reason", None)
            logprobs = choice.get("logprobs", [])
        else:
            accumulated_response = ""
            finish_reason = None
            logprobs = []

        logger.info(f"Finish reason: {finish_reason}")
        logger.info(
            f"Accumulated response length: {len(accumulated_response)}, content: {accumulated_response[:200] if accumulated_response else 'EMPTY'}"
        )

        return (
            accumulated_response,
            0,  # elapsed_time_ms not available in standard response
            logprobs,
            finish_reason,
        )
    except requests.exceptions.HTTPError as e:
        if e.response.status_code == 503:
            logger.error(
                f"Rate limit exceeded during next_edit_autocomplete HTTP request: {str(e)}"
            )
            return "", 0, [], "rate_limited"
        else:
            logger.error(
                f"HTTP error {e.response.status_code} during next_edit_autocomplete HTTP request: {str(e)}"
            )
            return "", 0, [], "http_error"
    except (requests.exceptions.Timeout, requests.exceptions.ConnectionError) as e:
        logger.error(
            f"Timeout or connection error during next_edit_autocomplete HTTP request: {str(e)}"
        )
        return "", 0, [], "timeout"


def get_session():
    """Get or create a requests Session for connection pooling"""
    global _session
    if _session is None:
        _session = requests.Session()
    return _session


def is_typing_quickly(
    recent_user_actions: list[UserAction], threshold_ms: int = 200, min_actions: int = 3
) -> bool:
    """
    Detect if the user is typing quickly by analyzing consecutive INSERT_CHAR actions.

    Args:
        recent_user_actions: List of recent user actions
        threshold_ms: Maximum average time between keystrokes to consider "quick typing" (default 200ms)
        min_actions: Minimum number of consecutive INSERT_CHAR actions to analyze (default 3)

    Returns:
        True if user appears to be typing quickly, False otherwise
    """
    if not recent_user_actions or len(recent_user_actions) < min_actions:
        return False

    # Find consecutive INSERT_CHAR actions from the end of the list
    consecutive_insert_actions = []
    for action in reversed(recent_user_actions):
        if action.action_type == "INSERT_CHAR" and action.timestamp > 0:
            consecutive_insert_actions.insert(
                0, action
            )  # Insert at beginning to maintain order
        else:
            break  # Stop at first non-INSERT_CHAR action

    if len(consecutive_insert_actions) < min_actions:
        return False

    # Calculate average time between consecutive INSERT_CHAR actions
    time_diffs = []
    for i in range(1, len(consecutive_insert_actions)):
        time_diff = (
            consecutive_insert_actions[i].timestamp
            - consecutive_insert_actions[i - 1].timestamp
        )
        time_diffs.append(time_diff)

    if not time_diffs:
        return False

    avg_time_diff = sum(time_diffs) / len(time_diffs)

    return avg_time_diff <= threshold_ms


def _fetch_next_edits_core(
    file_path: str,
    file_contents: str,
    recent_changes: str,
    cursor_position: int,
    original_file_contents: str | None,
    code_block: str,
    prefix: str,
    suffix: str,
    autocomplete_id: str,
    block_start_index: int,
    is_retrieval: bool,
    file_chunks: list[FileChunkData] = None,
    retrieval_chunks: list[FileChunkData] = None,
    recent_user_actions: list[UserAction] = None,
    recent_changes_high_res: str = "",
    changes_above_cursor: bool = False,
    do_insert_cursor: bool = True,
):
    # Initialize mutable default arguments
    if file_chunks is None:
        file_chunks = []
    if retrieval_chunks is None:
        retrieval_chunks = []
    if recent_user_actions is None:
        recent_user_actions = []
    if not code_block:
        metadata = AutocompleteMetadata(
            exit_reason="no_code_block", is_retrieval_autocomplete=is_retrieval
        )
        return (
            [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
            [],
            "",
            False,
            metadata,
        )

    # disable non ghost-text for now, will rework later
    force_ghost_text = False

    if recent_user_actions and recent_user_actions[-1].action_type == "INSERT_CHAR":
        # Don't force ghost text if user is typing quickly (likely to make typos)
        # if not is_typing_quickly(recent_user_actions):
        force_ghost_text = True

    if not recent_user_actions:
        force_ghost_text = True

    relative_cursor_position = cursor_position - file_contents.find(code_block)
    cleaned_code_block = code_block
    relative_cursor_line = get_line_number_from_position(
        code_block, relative_cursor_position
    )
    if do_insert_cursor:
        code_block = (
            code_block[:relative_cursor_position]
            + "<|cursor|>"
            + code_block[relative_cursor_position:]
        )
    only_changed_lines, prev_section, prev_sections = (
        format_recent_changes_and_prev_section(recent_changes, code_block)
    )

    if recent_changes_high_res:
        _, _, prev_sections = format_recent_changes_and_prev_section(
            recent_changes_high_res, code_block
        )

    # removing force ghost text for now, later, we should regenerate current line
    # do not force ghost text at EOF
    # disable partial prefill (ghost text on current line) for local model
    is_at_eof = relative_cursor_position == len(cleaned_code_block)
    if force_ghost_text and not is_at_eof and NEXT_EDIT_AUTOCOMPLETE_ENDPOINT:
        prefill = cleaned_code_block[:relative_cursor_position]
        pretokens = pretokenize_regex.findall(prefill)
        regex_based_prefill = "".join(pretokens[:-1] if pretokens else [])
        prefill = regex_based_prefill
        forced_prefix = (
            cleaned_code_block[:relative_cursor_position].removeprefix(prefill)
            if force_ghost_text
            else ""
        )
    else:
        if changes_above_cursor:
            forced_prefix = ""
            prefill = cleaned_code_block[:relative_cursor_position]
            prefilled_lines = prefill.splitlines(True)

            # Keep the first NUM_LINES_ABOVE lines
            # Never rstrip newlines since it breaks tokenization

            NUM_LINES_ABOVE = 1
            before_split = "".join(prefilled_lines[:NUM_LINES_ABOVE])
            after_split = "".join(prefilled_lines[NUM_LINES_ABOVE:])

            for char in after_split:
                if char == "\n":
                    before_split += "\n"
                else:
                    break

            prefill = "".join(before_split)
        else:
            prefill = ""
            forced_prefix = ""

    # retrieval results are placed after the recent changes for optimal KV cache hit-rate
    MAX_RETRIEVAL_TOKENS_COUNT = 2048
    packed_retrieval_chunks = pack_items_for_prompt(
        retrieval_chunks,
        string_function=lambda chunk: chunk.to_string(),
        token_limit=MAX_RETRIEVAL_TOKENS_COUNT,
        char_token_ratio=3.5,
        truncate_from_end=False,
    )
    retrieval_results = "".join(
        [f"\n{chunk.to_string()}" for i, chunk in enumerate(packed_retrieval_chunks)]
    )

    # Count actual retrieval chunks that made it past truncation
    retrieval_chunks_count = len(packed_retrieval_chunks)
    retrieval_chunks_char_count = sum(
        len(chunk.content) for chunk in packed_retrieval_chunks
    )
    retrieval_chunks_line_count = sum(
        len(chunk.content.splitlines()) for chunk in packed_retrieval_chunks
    )

    if code_block.endswith("\n") and prev_section.endswith("\n"):
        code_block = code_block.removesuffix("\n")
        prev_section = prev_section.removesuffix("\n")

    initial_file = get_lines_around_cursor(original_file_contents, cursor_position)

    formatted_prompt = (
        prompt.format(
            file_path=file_path,
            recent_changes=only_changed_lines,
            prev_section=prev_section,
            code_block=code_block,
            retrieval_results=retrieval_results,
            initial_file=initial_file,
            start_line=relative_cursor_line + 1,
            end_line=relative_cursor_line + len(code_block.splitlines()) + 1,
        )
        + f"\n{prefill}"
    )

    truncation_record = PromptTruncationRecord(
        autocomplete_id=autocomplete_id,
        original_prompt_length=len(formatted_prompt),
        max_seq_len=MAX_INPUT_TOKENS_COUNT,
        lower_bound=CHARACTER_BOUND_TO_CHECK_TOKENIZATION,
        file_path=file_path,
        cursor_position=cursor_position,
        initial_file=initial_file,
        retrieval_results=retrieval_results,
        retrieval_results_length=len(retrieval_results),
        recent_changes=only_changed_lines,
        prev_section=prev_section,
        code_block=code_block,
        suffix=suffix,
        prefix=prefix,
        prefill=prefill,
        file_chunks=file_chunks if file_chunks else [],
        file_chunks_available=len(file_chunks) if file_chunks else 0,
        final_prompt_length=len(formatted_prompt),
        truncation_occurred=False,
        truncation_reason="",
        file_chunks_used=0,
        start_line=relative_cursor_line + 1,
        end_line=relative_cursor_line + len(code_block.splitlines()) + 1,
    )

    formatted_file_chunks = "".join([chunk.to_string() for chunk in file_chunks])
    # Track file chunk metrics that made it past truncation
    file_chunks_count = -1
    file_chunks_char_count = -1
    file_chunks_line_count = -1

    if (
        len(formatted_prompt) + len(formatted_file_chunks)
        > CHARACTER_BOUND_TO_CHECK_TOKENIZATION
    ):
        with Timer("Prompt Truncation", precision=4, min_time=0.0):
            (
                formatted_prompt,
                file_chunks_count,
                file_chunks_char_count,
                file_chunks_line_count,
            ) = truncate_prompt_when_near_limit(
                truncation_record=truncation_record,
            )
        if not formatted_prompt:
            metadata = AutocompleteMetadata(
                exit_reason="prompt_truncation_failed",
                retrieval_chunks_count=-1,
                retrieval_chunks_char_count=-1,
                retrieval_chunks_line_count=-1,
                file_chunks_count=-1,
                file_chunks_char_count=-1,
                file_chunks_line_count=-1,
                is_retrieval_autocomplete=is_retrieval,
            )
            return (
                [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
                [],
                "",
                False,
                metadata,
            )
    else:
        # add back the file chunks
        formatted_prompt = formatted_file_chunks + formatted_prompt
        # All file chunks made it
        file_chunks_count = len(file_chunks)
        file_chunks_char_count = sum(len(chunk.content) for chunk in file_chunks)
        file_chunks_line_count = sum(
            len(chunk.content.splitlines()) for chunk in file_chunks
        )
    # end section on prompt truncation

    # Create base metadata with all chunk counts - will update exit_reason as needed
    base_metadata = AutocompleteMetadata(
        retrieval_chunks_count=retrieval_chunks_count,
        retrieval_chunks_char_count=retrieval_chunks_char_count,
        retrieval_chunks_line_count=retrieval_chunks_line_count,
        file_chunks_count=file_chunks_count,
        file_chunks_char_count=file_chunks_char_count,
        file_chunks_line_count=file_chunks_line_count,
        is_retrieval_autocomplete=is_retrieval,
    )

    data = {"prompt": formatted_prompt}

    stop = ["<|endoftext|>", "<|file_sep|>"]

    cursor_line = get_line_number_from_position(
        cleaned_code_block, relative_cursor_position
    )
    cursor_line_text = cleaned_code_block.splitlines(True)[cursor_line].strip("\n")

    # truncate long lines in formatted_prompt ~0.0001 seconds
    formatted_prompt = truncate_long_lines(formatted_prompt)

    with Timer("Autocomplete", precision=4):
        if NEXT_EDIT_AUTOCOMPLETE_ENDPOINT:
            # Use remote HTTP endpoint
            with Timer("Autocomplete HTTP", precision=4):
                try:
                    completion, latency, logprobs, finish_reason = (
                        fetch_next_edits_http(
                            formatted_prompt=formatted_prompt,
                            stop=stop,
                            cleaned_code_block=cleaned_code_block,
                            file_contents=file_contents,
                            cursor_position=relative_cursor_position,
                            prefix=forced_prefix,
                            prefill=prefill,
                            force_ghost_text=force_ghost_text,
                            relative_cursor_line=relative_cursor_line,
                        )
                    )
                except PromptTooLongError as e:
                    logger.warning(
                        f"Prompt too long for line '{cursor_line_text}', error: {e}, returning empty results"
                    )
                    metadata = replace(base_metadata, exit_reason="prompt_too_long")
                    return (
                        [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
                        [],
                        formatted_prompt,
                        False,
                        metadata,
                    )
                except Exception:
                    # Re-raise other exceptions
                    raise
        else:
            # Use local llama-cpp-python model
            with Timer("Autocomplete Local", precision=4):
                try:
                    completion, latency, logprobs, finish_reason = generate_completion(
                        prompt=formatted_prompt,
                        stop=stop,
                        max_tokens=AUTOCOMPLETE_OUTPUT_MAX_TOKENS,
                        temperature=0.0,
                        prefix=forced_prefix,
                    )
                except RequestCancelled:
                    logger.info("Request cancelled by newer request")
                    metadata = replace(base_metadata, exit_reason="cancelled")
                    return (
                        [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
                        [],
                        formatted_prompt,
                        False,
                        metadata,
                    )
                except Exception as e:
                    logger.error(f"Local model error: {e}")
                    raise
        if not completion:
            metadata = replace(base_metadata, exit_reason="no_completion_received")
            return (
                [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
                [],
                formatted_prompt,
                False,
                metadata,
            )

    # print(f"Received completion request for cursor line '{cursor_line_text}'")
    # complete_completion = prefill + completion
    # completion_line = complete_completion.splitlines(True)[cursor_line].strip("\n")
    # print(f"Response: {completion_line}")

    if DEBUG:
        print(formatted_prompt)
        print("\n\n")
        print(completion)
        print("Forced prefix", forced_prefix)
        print("Recent changes:")
        print(recent_changes)
    # print(f"Time taken: {(time.time() - start_time) * 1000} milliseconds")

    if completion == "":
        # Bandaid fix -- the root cause is that the deployment may be down.
        logger.warning(
            f"Completion is empty for line '{cursor_line_text}', likely due to deployment issues."
        )
        metadata = replace(base_metadata, exit_reason="empty_completion")
        return (
            [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
            [],
            formatted_prompt,
            False,
            metadata,
        )

    if completion.startswith("<|") or completion.removeprefix(forced_prefix).startswith(
        "<|"
    ):
        # Bandaid fix -- root cause is it's probably a special token.
        logger.warning(
            f"Completion starts with special token for line '{cursor_line_text}'."
        )
        metadata = replace(base_metadata, exit_reason="special_token_in_completion")
        return (
            [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
            [],
            formatted_prompt,
            False,
            metadata,
        )

    if not completion.startswith(forced_prefix):
        # Sometimes forced prefix is not respected by CPC
        logger.warning(
            f"Forced prefix not respected by completion for line '{cursor_line_text}'."
        )
        metadata = replace(base_metadata, exit_reason="forced_prefix_not_respected")
        return (
            [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
            [],
            formatted_prompt,
            False,
            metadata,
        )

    original_completion = completion
    completion = prefill + completion

    if is_pure_insertion_above_cursor(
        cleaned_code_block, completion, relative_cursor_position
    ):
        # Pure insertion above cursor detected, return empty completion
        logger.warning("Pure insertion above cursor detected.")
        metadata = replace(base_metadata, exit_reason="pure_insertion_above_cursor")
        return (
            [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
            [],
            formatted_prompt,
            False,
            metadata,
        )

    if is_large_diff_above_cursor(
        cleaned_code_block, completion, relative_cursor_position
    ):
        # Large diff above cursor detected (>5 lines added with >1 line deleted), return empty completion
        logger.warning("Large diff above cursor detected.")
        metadata = replace(base_metadata, exit_reason="large_diff_above_cursor")
        return (
            [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
            [],
            formatted_prompt,
            False,
            metadata,
        )

    # there's a bug in the training data which causes this to be generated sometimes, will fix later
    if completion.rstrip("\n").endswith(" No newline at end of file"):
        completion, _ = completion.split(" No newline at end of file", maxsplit=1)

    completion = (
        # strip_leading_empty_newlines(data.get("response", "")).removesuffix("<|file_sep|>") or cleaned_code_block
        strip_leading_empty_newlines(completion).removesuffix("<|file_sep|>")
        or cleaned_code_block
    )
    if "<|cursor|>" not in cleaned_code_block:
        completion = completion.replace("<|cursor|>", "")

    cleaned_code_lines = cleaned_code_block.splitlines(True)
    completion_lines = completion.splitlines(True)
    if len(completion_lines) - len(cleaned_code_lines) > 20:
        # cut the completion down to at most 20 additional lines
        completion = "".join(completion_lines[: len(cleaned_code_lines) + 20])
    # confidence = data.get("confidence", 0.0)

    # if completion.startswith(cleaned_code_block) and completion.removeprefix(cleaned_code_block) in file_contents:
    #     logger.warning("Completion starts with cleaned code block and is in file contents.")
    #     completion = cleaned_code_block

    # # multi-line deletions are probably bugs so let's disable it.
    # if len(cleaned_code_block.splitlines()) - len(completion.splitlines()) > 2:
    #     completion = cleaned_code_block

    did_hit_max_tokens = finish_reason == "length"

    if did_hit_max_tokens:  # here check
        logger.warning("Completion length exceeds max_tokens")
        metadata = replace(base_metadata, exit_reason="hit_max_tokens")
        return (
            [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
            [],
            formatted_prompt,
            False,
            metadata,
        )

    completions = select_best_hunk_from_completion(
        completion,
        cleaned_code_block,
        file_contents,
        cursor_position,
        autocomplete_id,
        logprobs,
    )

    if completion.strip("\n") == cleaned_code_block.strip("\n"):
        logger.warning("No changes made")
        should_continue = not did_hit_max_tokens
        metadata = replace(base_metadata, exit_reason="no_changes_made")
        return (
            completions,
            completions,
            formatted_prompt,
            should_continue,
            metadata,
        )

    # Parts of the completion may be removed in select_best_hunk_from_completion. Apply to cleaned_code_block to get actual completion
    code_block_with_completions = apply_completions_to_code_block(
        completions, file_contents, cleaned_code_block
    )
    # ghost_text = is_single_line_ghost_text(code_block_with_completions, cleaned_code_block, relative_cursor_position)

    # Non ghost texts are annoying if they revert to a previous file state
    for section in prev_sections:
        if is_equal_ignoring_newlines(code_block_with_completions, section):
            logger.warning(f"Revert detected for section '{section.strip()}'.")
            metadata = replace(base_metadata, exit_reason="revert_detected")
            return (
                [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
                [],
                formatted_prompt,
                False,
                metadata,
            )

    # This checks if user is on an under-indented line
    cleaned_lines = cleaned_code_block.splitlines(True)
    current_line = (
        ""
        if relative_cursor_line >= len(cleaned_lines)
        else cleaned_lines[relative_cursor_line]
    )
    is_pure_whitespace = current_line.strip() == "" and len(current_line) > 0

    if is_pure_whitespace and completions:
        code_block_with_first_completion = apply_completions_to_code_block(
            [completions[0]], file_contents, cleaned_code_block
        )

        # Case 1: Current line is non-empty blank line and suggestion deletes pure whitespace at cursor position
        first_completion = completions[0]
        is_pure_whitespace_deleted = (
            first_completion.completion == ""
            and file_contents[
                first_completion.start_index : first_completion.end_index
            ].strip()
            == ""
            and first_completion.end_index in (cursor_position, cursor_position + 1)
        )
        if is_pure_whitespace_deleted:
            logger.warning(
                "Current line is non-empty blank line and suggestion deletes pure whitespace at cursor position."
            )
            metadata = replace(base_metadata, exit_reason="pure_whitespace_deleted")
            return (
                [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
                [],
                formatted_prompt,
                False,
                metadata,
            )

        # Case 2: Current line is non-empty blank line and ghost text starts with whitespace
        # Only apply first completion to get ghost text
        ghost_text = is_single_line_ghost_text(
            code_block_with_first_completion,
            cleaned_code_block,
            relative_cursor_position,
        )
        if ghost_text and ghost_text.startswith(" "):
            logger.warning(
                "Current line is non-empty blank line and ghost text starts with whitespace."
            )
            metadata = replace(
                base_metadata, exit_reason="ghost_text_starts_with_whitespace"
            )
            return (
                [AutocompleteResult(0, 0, "", 0.0, autocomplete_id)],
                [],
                formatted_prompt,
                False,
                metadata,
            )

    metadata = replace(base_metadata, exit_reason="success")
    return completions, completions, formatted_prompt, True, metadata


def truncate_prompt_when_near_limit(
    truncation_record: PromptTruncationRecord,
) -> tuple[str | None, int, int, int]:
    """
    Truncate prompt when near token limit.

    Returns:
        Tuple of (final_prompt, file_chunks_used, file_chunks_char_count, file_chunks_line_count)
    """
    formatted_prompt_minimal = (
        prompt.format(
            file_path=truncation_record.file_path,
            recent_changes=truncation_record.recent_changes,
            prev_section=truncation_record.prev_section,
            code_block=truncation_record.code_block,
            retrieval_results="",
            initial_file=truncation_record.initial_file,
            start_line=truncation_record.start_line,
            end_line=truncation_record.end_line,
        )
        + f"\n{truncation_record.prefill}"
    )

    # Case zero: this is too long, we should not even tokenize as it takes 200 ms in worst case
    if len(formatted_prompt_minimal) > CHARACTER_BOUND_TO_SKIP_TOKENIZATION:
        return None, 0, 0, 0
    formatted_prompt_minimal_token_count = estimate_token_count(
        formatted_prompt_minimal
    )
    retrieval_results_token_count = estimate_token_count(
        truncation_record.retrieval_results
    )
    chunks_token_count = [
        estimate_token_count(chunk.content) for chunk in truncation_record.file_chunks
    ]

    # Case 1: everything fits; return the full prompt
    if (
        formatted_prompt_minimal_token_count
        + retrieval_results_token_count
        + sum(chunks_token_count)
        <= MAX_INPUT_TOKENS_COUNT
    ):
        formatted_file_chunks = "".join(
            [chunk.to_string() for chunk in truncation_record.file_chunks]
        )
        final_prompt = formatted_file_chunks + (
            prompt.format(
                file_path=truncation_record.file_path,
                recent_changes=truncation_record.recent_changes,
                prev_section=truncation_record.prev_section,
                code_block=truncation_record.code_block,
                retrieval_results=truncation_record.retrieval_results,
                initial_file=truncation_record.initial_file,
                start_line=truncation_record.start_line,
                end_line=truncation_record.end_line,
            )
            + f"\n{truncation_record.prefill}"
        )
        file_chunks_count = len(truncation_record.file_chunks)
        file_chunks_char_count = sum(
            len(chunk.content) for chunk in truncation_record.file_chunks
        )
        file_chunks_line_count = sum(
            len(chunk.content.splitlines()) for chunk in truncation_record.file_chunks
        )
    # Case 2: minimal autocomplete is too long
    elif formatted_prompt_minimal_token_count > MAX_INPUT_TOKENS_COUNT:
        final_prompt = ""
        file_chunks_count = 0
        file_chunks_char_count = 0
        file_chunks_line_count = 0
    # Case 3: drop all file chunks
    elif (
        formatted_prompt_minimal_token_count + retrieval_results_token_count
        > MAX_INPUT_TOKENS_COUNT
    ):
        final_prompt = (
            prompt.format(
                file_path=truncation_record.file_path,
                recent_changes=truncation_record.recent_changes,
                prev_section=truncation_record.prev_section,
                code_block=truncation_record.code_block,
                retrieval_results="",
                initial_file=truncation_record.initial_file,
                start_line=truncation_record.start_line,
                end_line=truncation_record.end_line,
            )
            + f"\n{truncation_record.prefill}"
        )
        file_chunks_count = 0
        file_chunks_char_count = 0
        file_chunks_line_count = 0
    # Case 4: drop some file chunks
    else:
        formatted_prompt_with_retrieval_chunks = (
            prompt.format(
                file_path=truncation_record.file_path,
                recent_changes=truncation_record.recent_changes,
                prev_section=truncation_record.prev_section,
                code_block=truncation_record.code_block,
                retrieval_results=truncation_record.retrieval_results,
                initial_file=truncation_record.initial_file,
                start_line=truncation_record.start_line,
                end_line=truncation_record.end_line,
            )
            + f"\n{truncation_record.prefill}"
        )
        current_token_count = (
            formatted_prompt_minimal_token_count + retrieval_results_token_count
        )

        partial_formatted_file_chunks = ""
        all_chunks_token_count = 0
        chunks_that_fit = []
        for chunk, chunk_token_count in zip(
            truncation_record.file_chunks, chunks_token_count
        ):
            current_chunk_str = chunk.to_string()
            all_chunks_token_count += chunk_token_count
            if current_token_count + all_chunks_token_count >= MAX_INPUT_TOKENS_COUNT:
                break
            partial_formatted_file_chunks += current_chunk_str
            chunks_that_fit.append(chunk)
        final_prompt = (
            partial_formatted_file_chunks + formatted_prompt_with_retrieval_chunks
        )
        file_chunks_count = len(chunks_that_fit)
        file_chunks_char_count = sum(len(chunk.content) for chunk in chunks_that_fit)
        file_chunks_line_count = sum(
            len(chunk.content.splitlines()) for chunk in chunks_that_fit
        )

    return (
        final_prompt,
        file_chunks_count,
        file_chunks_char_count,
        file_chunks_line_count,
    )


hex_hash_pattern = re.compile(r"[a-f0-9]{32,}")
base64_pattern = re.compile(r"[A-Za-z0-9+/]{40,}={0,2}")  # base64 strings 40+ chars


def should_disable_autocomplete(file_contents: str) -> tuple[bool, str]:
    """
    Check if autocomplete should be disabled based on file characteristics.

    Args:
        file_contents: The content of the file to check

    Returns:
        Tuple of (should_disable, reason) where should_disable is True if autocomplete
        should be disabled and reason explains why
    """
    if not file_contents:
        return False, ""

    # Check number of characters
    num_chars = len(file_contents)
    if num_chars > 10_000_000:  # 10M characters
        return True, f"file too large: {num_chars:,} characters > 10M"

    lines = file_contents.splitlines()
    if not lines:
        return False, ""

    # Check number of lines
    num_lines = len(lines)
    if num_lines > 50_000:  # 50k lines
        return True, f"too many lines: {num_lines:,} lines > 50k"

    # Check average line length
    total_chars = sum(len(line) for line in lines)
    avg_line_length = total_chars / num_lines
    if avg_line_length > 240:
        return True, f"average line length {avg_line_length:.1f} > 240"

    if num_lines > 1000:
        length_counter = Counter(len(line) for line in lines)
        if (
            sum(length_counter[length] for length in length_counter if length > 120)
            > num_lines * 0.3
        ):
            return True, "30% of lines are > 120 chars"

        hash_lines = sum(
            1
            for line in lines
            if hex_hash_pattern.search(line) or base64_pattern.search(line)
        )
        percentage_hash_lines = (hash_lines / num_lines) * 100 if num_lines > 0 else 0

        if percentage_hash_lines > 10:
            return True, "10% of lines are hashes"

    return False, ""


def fetch_next_edits(
    file_path: str,
    file_contents: str,
    recent_changes: str,
    cursor_position: int,
    original_file_contents: str | None = None,
    file_chunks: list[FileChunkData] = None,
    retrieval_chunks: list[FileChunkData] = None,
    recent_user_actions: list[UserAction] = None,
    recent_changes_high_res: str = "",
    changes_above_cursor: bool = False,
    is_new_user: bool = False,
    editor_diagnostics: list[EditorDiagnostic] = None,
):
    if is_new_user:
        logger.debug("New user detected, disabling changes_above_cursor")
        changes_above_cursor = False

    # Check if autocomplete should be disabled based on file characteristics
    should_disable, reason = should_disable_autocomplete(file_contents)
    if should_disable:
        logger.debug(f"Disabling autocomplete: {reason}")
        autocomplete_id = uuid.uuid4().hex
        yield (
            AutocompleteResult(0, 0, "", 0, autocomplete_id),
            [],
            "",
            AutocompleteMetadata(
                exit_reason="autocomplete_disabled",
                reason=reason,
                is_retrieval_autocomplete=False,
            ),
        )
        return

    autocomplete_id = uuid.uuid4().hex
    if original_file_contents is None:
        original_file_contents = file_contents
    if file_chunks is None:
        file_chunks = []

    cursor_position = adjust_cursor_position_from_utf16(file_contents, cursor_position)
    code_block, prefix, suffix, block_start_index = get_block_at_cursor(
        file_contents, cursor_position
    )

    if should_disable_for_code_block(code_block):
        logger.debug("Disabling autocomplete: long lines")
        autocomplete_id = uuid.uuid4().hex
        yield (
            AutocompleteResult(0, 0, "", 0, autocomplete_id),
            [],
            "",
            AutocompleteMetadata(
                exit_reason="autocomplete_disabled_long_lines",
                reason="long_lines_code_block",
                is_retrieval_autocomplete=False,
            ),
        )
        return

    # truncate each retrieval_chunk to MAX_RETRIEVAL_CHUNK_SIZE_LINES
    for retrieval_chunk in retrieval_chunks:
        retrieval_chunk.content = "".join(
            retrieval_chunk.content.splitlines(True)[:MAX_RETRIEVAL_CHUNK_SIZE_LINES]
        )

    # Limit chunks for local model to reduce prompt eval latency
    if not NEXT_EDIT_AUTOCOMPLETE_ENDPOINT:
        file_chunks = file_chunks[:1]
        retrieval_chunks = retrieval_chunks[:1]

    completions, all_completions, formatted_prompt, should_continue, metadata = (
        _fetch_next_edits_core(
            file_path=file_path,
            file_contents=file_contents,
            recent_changes=recent_changes,
            cursor_position=cursor_position,
            original_file_contents=original_file_contents,
            code_block=code_block,
            prefix=prefix,
            suffix=suffix,
            autocomplete_id=autocomplete_id,
            block_start_index=block_start_index,
            is_retrieval=False,
            file_chunks=file_chunks,
            retrieval_chunks=retrieval_chunks,
            recent_user_actions=recent_user_actions,
            recent_changes_high_res=recent_changes_high_res,
            changes_above_cursor=changes_above_cursor,
        )
    )

    if not should_continue:
        if all_completions:
            yield all_completions[0], all_completions, formatted_prompt, metadata
        else:
            yield (
                AutocompleteResult(0, 0, "", 0, autocomplete_id),
                [],
                formatted_prompt,
                metadata,
            )
        return

    if all_completions and not all(
        (
            not completion.completion.strip("\n")
            and completion.start_index == completion.end_index
        )
        for completion in all_completions
    ):
        yield all_completions[0], all_completions, formatted_prompt, metadata
        return

    with Timer(min_time=0.001, precision=3, name="find_best_matching_block"):
        retrieved_code_block, block_start_offset, is_block_after_cursor, diagnostic = (
            find_best_matching_block(
                file_contents,
                recent_changes,
                cursor_position=cursor_position,
                block_size=6,
                editor_diagnostics=editor_diagnostics,
            )
        )

        # if diagnostic, pass it in as an additional retrieval chunk
        if diagnostic:
            file_contents_lines = file_contents.splitlines()
            diagnostic_line = (
                file_contents_lines[diagnostic.line_number]
                if diagnostic.line_number < len(file_contents_lines)
                else ""
            )
            # add it as the first one
            retrieval_chunks = [
                FileChunkData(
                    content=f"{diagnostic.message} at line {diagnostic.line_number}:\n{diagnostic_line}",
                    file_path="diagnostics",
                    start_line=1,
                    end_line=2,
                )
            ] + retrieval_chunks

    if not retrieved_code_block:
        yield (
            AutocompleteResult(0, 0, "", 0, autocomplete_id),
            [],
            formatted_prompt,
            AutocompleteMetadata(
                exit_reason="no_retrieved_code_block", is_retrieval_autocomplete=True
            ),
        )
        return

    prefix_lines = file_contents[:block_start_offset].splitlines(True)
    retrieved_prefix = "".join(prefix_lines[-NUM_LINES_BEFORE:])

    num_retrieved_lines = len(retrieved_code_block.splitlines())
    num_suffix_lines = max(
        0, NUM_LINES_AFTER + 1 - num_retrieved_lines
    )  # +1 to include the cursor line

    suffix_lines = file_contents[
        block_start_offset + len(retrieved_code_block) :
    ].splitlines(True)
    retrieved_suffix = "".join(suffix_lines[:num_suffix_lines])
    cursor_position_in_block = block_start_offset + len(
        retrieved_code_block.splitlines()[0]
    )
    full_block = retrieved_prefix + truncate_code_block_by_tokens(
        retrieved_code_block + retrieved_suffix
    )

    if should_disable_for_code_block(full_block):
        logger.debug("Disabling autocomplete: long lines")
        autocomplete_id = uuid.uuid4().hex
        yield (
            AutocompleteResult(0, 0, "", 0, autocomplete_id),
            [],
            "",
            AutocompleteMetadata(
                exit_reason="autocomplete_disabled_long_lines",
                reason="long_lines_code_block",
                is_retrieval_autocomplete=True,
            ),
        )
        return

    # Set cursor position to end of the suffix_lines assignment line
    completions, all_completions, formatted_prompt, _, metadata = (
        _fetch_next_edits_core(
            file_path=file_path,
            file_contents=file_contents,
            recent_changes=recent_changes,
            cursor_position=cursor_position_in_block,
            original_file_contents=original_file_contents,
            code_block=full_block,
            prefix=retrieved_prefix,
            suffix=retrieved_suffix,
            autocomplete_id=autocomplete_id,
            block_start_index=block_start_offset,
            is_retrieval=True,
            file_chunks=file_chunks,
            retrieval_chunks=retrieval_chunks,
            recent_user_actions=recent_user_actions
            + [
                UserAction(
                    action_type="CURSOR_MOVEMENT",
                    offset=cursor_position_in_block,
                    line_number=get_line_number_from_position(
                        file_contents=file_contents, position=cursor_position_in_block
                    ),
                    file_path=file_path,
                )
            ],
            recent_changes_high_res=recent_changes_high_res,
            changes_above_cursor=changes_above_cursor,
        )
    )
    if all_completions and recent_changes and all_completions[0].completion.strip():
        yield all_completions[0], all_completions, formatted_prompt, metadata
    else:
        yield (
            AutocompleteResult(0, 0, "", 0, autocomplete_id),
            [],
            formatted_prompt,
            metadata,
        )
    return
