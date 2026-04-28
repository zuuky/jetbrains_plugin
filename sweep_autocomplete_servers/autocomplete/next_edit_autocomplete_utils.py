from __future__ import annotations

import difflib
from dataclasses import dataclass

from loguru import logger

CHARS_PER_TOKEN = 3.5
AUTOCOMPLETE_OUTPUT_MAX_TOKENS = 1024
AUTOCOMPLETE_TRUNCATION_LINE_LENGTH = 600
AUTOCOMPLETE_MAXIMUM_LINE_LENGTH = 1000


@dataclass
class PromptTooLongError(Exception):
    message: str


def adjust_cursor_position_from_utf16(
    file_contents: str, utf16_cursor_position: int
) -> int:
    """
    Convert cursor position from UTF-16 (used by IntelliJ/JVM) to UTF-8 byte position.

    IntelliJ uses UTF-16 encoding where emojis and other Unicode characters may take 2 bytes,
    but Python uses UTF-8 where they can take 3-4 bytes. This function converts the cursor
    position to match Python's string indexing.

    Args:
        file_contents: The file content as a Python string
        utf16_cursor_position: Cursor position as reported by IntelliJ (UTF-16 based)

    Returns:
        Adjusted cursor position for Python string indexing
    """
    if utf16_cursor_position <= 0:
        return utf16_cursor_position

    # Convert string to UTF-16 to match IntelliJ's encoding
    utf16_bytes = file_contents.encode("utf-16le")

    # Ensure we don't exceed the actual content length
    max_utf16_pos = len(utf16_bytes) // 2  # Each UTF-16 character is 2 bytes
    actual_utf16_pos = min(utf16_cursor_position, max_utf16_pos)

    # Get the substring up to the cursor position in UTF-16
    utf16_substring_bytes = utf16_bytes[: actual_utf16_pos * 2]

    try:
        # Decode back to get the corresponding Python string
        utf16_substring = utf16_substring_bytes.decode("utf-16le")
        # The length of this substring is the correct cursor position in Python
        return len(utf16_substring)
    except UnicodeDecodeError:
        # If we hit a decode error, we might be in the middle of a surrogate pair
        # Try backing up by one UTF-16 unit
        if actual_utf16_pos > 0:
            try:
                utf16_substring_bytes = utf16_bytes[: (actual_utf16_pos - 1) * 2]
                utf16_substring = utf16_substring_bytes.decode("utf-16le")
                return len(utf16_substring)
            except UnicodeDecodeError:
                pass

        # Fallback: return the original position
        return utf16_cursor_position


def extract_diff_parts(hunk: str, num_context_lines: int = 0) -> tuple[str, str]:
    """Extract the old and new code from a diff hunk.

    Args:
        hunk: The diff hunk string starting with @@ markers
        num_context_lines: Number of context lines to keep (default: 0, set to -1 for all)

    Returns:
        Tuple of (old_code, new_code) with the - and + markers removed
    """
    lines = hunk.splitlines(True)

    # Skip the @@ header line
    content_lines = [line for line in lines if not line.startswith("@@")]

    if num_context_lines == -1:
        # Keep all lines
        old_code = []
        new_code = []
        for line in content_lines:
            if line.startswith("-"):
                old_code.append(line[1:])
            elif line.startswith("+"):
                new_code.append(line[1:])
            else:
                old_code.append(line[1:])
                new_code.append(line[1:])

        return "".join(old_code), "".join(new_code)

    # Find the range of changed lines (- and + lines)
    changed_indices = []
    for i, line in enumerate(content_lines):
        if line.startswith("-") or line.startswith("+"):
            changed_indices.append(i)

    if not changed_indices:
        # No changes, return empty
        return "", ""

    # Determine the range to include with context
    start_change = min(changed_indices)
    end_change = max(changed_indices)

    # Calculate context range
    start_idx = max(0, start_change - num_context_lines)
    end_idx = min(len(content_lines), end_change + num_context_lines + 1)

    # Extract the relevant lines
    relevant_lines = content_lines[start_idx:end_idx]

    old_code = []
    new_code = []

    for line in relevant_lines:
        if line.startswith("-"):
            old_code.append(line[1:])
        elif line.startswith("+"):
            new_code.append(line[1:])
        else:
            # Context line - add to both
            old_code.append(line[1:])
            new_code.append(line[1:])

    return "".join(old_code), "".join(new_code)


def filter_whitespace_only_hunks(hunks: list[str]) -> list[str]:
    """
    Filter out hunks that only contain whitespace changes.

    A hunk is considered whitespace-only if the old and new code are identical
    when stripped of whitespace.

    Args:
        hunks: List of diff hunks, each starting with "File: " marker

    Returns:
        List of hunks with whitespace-only changes removed
    """
    filtered_hunks = []
    for hunk in hunks:
        first_line, *rest = hunk.splitlines(True)
        old_code, new_code = extract_diff_parts("".join(rest))
        # Skip hunks where the only difference is whitespace
        if old_code.strip() != new_code.strip():
            filtered_hunks.append(hunk)
    return filtered_hunks


def split_into_hunks(diff: str) -> list[str]:
    """Split a diff string into individual hunks.

    Args:
        diff: The full diff string

    Returns:
        List of individual diff hunks, each starting with @@ marker
    """
    hunks = []
    current_hunk = []

    for line in diff.splitlines():
        if line.startswith("File: ") and current_hunk:
            hunks.append("\n".join(current_hunk))
            current_hunk = []
        current_hunk.append(line)

    if current_hunk:
        hunks.append("\n".join(current_hunk))

    return hunks


def get_line_number_from_position(file_contents: str, position: int) -> int:
    """
    Convert a character position to a line number in the file.
    Optimized to avoid scanning through all lines.

    Args:
        file_contents: The full contents of the file
        position: The character position in the file

    Returns:
        The line number (0-indexed) corresponding to the position
    """
    if position <= 0:
        return 0

    if position >= len(file_contents):
        return len(file_contents.splitlines()) - 1

    # Count newlines up to the position - much faster than splitting into lines
    return file_contents[:position].count("\n")


def get_lines_around_cursor(file_contents: str, cursor_position: int) -> str:
    """
    Return a fixed span sliced into overlapping CHUNK_SIZE length chunks with STRIDE,
    choosing the chunk whose center is closest to the cursor line.

    The file is conceptually chunked starting at line 0, then STRIDE, 2*STRIDE, ... Each chunk
    contains up to CHUNK_SIZE lines. We pick the stride-aligned chunk that best "centers"
    around the cursor (i.e., whose center is closest to the cursor line). Chunks are
    truncated at EOF if needed.

    Notes:
    - For files with <= CHUNK_SIZE lines, return the full file.

    Args:
        file_contents: The full contents of the file as a string.
        cursor_position: The cursor position as a character offset.
    Returns:
        A string containing the fixed chunk that contains the cursor line.
    """
    lines = file_contents.splitlines()

    CHUNK_SIZE = 300
    STRIDE = CHUNK_SIZE // 2
    LIMIT_TO_CHUNK = 800

    # Small files: just return the entire contents
    if len(lines) <= LIMIT_TO_CHUNK:
        return file_contents

    # Find the line number for the cursor position
    cursor_line = get_line_number_from_position(file_contents, cursor_position)

    # Choose the stride-aligned chunk whose center is nearest to the cursor
    # Ideal centered start (not necessarily stride-aligned)
    ideal_start = cursor_line - CHUNK_SIZE // 2
    # Convert to nearest stride-aligned index (banker's rounding acceptable)
    chunk_index = int(round(ideal_start / STRIDE))
    # Clamp to non-negative
    chunk_index = max(0, chunk_index)
    start_line = chunk_index * STRIDE  # multiple of 150: 0, 150, 300, 450, ...
    end_line = min(len(lines), start_line + CHUNK_SIZE)

    return "\n".join(lines[start_line:end_line])


def strip_leading_empty_newlines(completion: str) -> str:
    lines = completion.split("\n")

    start_index = 0
    while start_index < len(lines) and not lines[start_index].strip():
        start_index += 1

    return "\n".join(lines[start_index:])


def keep_only_changing_lines(changes: str) -> str:
    if not changes:
        return ""

    hunks = changes.split("\n@@")

    processed_hunks = []
    for i, hunk in enumerate(hunks):
        if not hunk.strip():
            continue
        lines = hunk.splitlines()
        if i > 0:
            lines = lines[1:]
        filtered_lines = [
            line
            for line in lines
            if line.startswith("+") or line.startswith("-") and len(line.strip()) > 1
        ]
        if filtered_lines:
            processed_hunks.append("\n".join(filtered_lines))

    return "\n".join(processed_hunks)


def extract_minimal_diff(original_code: str, new_code: str) -> tuple[str, int, int]:
    """Extract the minimal diff between original and new code with one line of context.

    Args:
        original_code: The original code block
        new_code: The new code block with changes

    Returns:
        Tuple of (minimal_diff, start_offset, end_offset) where offsets are relative to original_code
    """
    original_lines = original_code.splitlines(keepends=True)
    new_lines = new_code.splitlines(keepends=True)

    start_diff = 0
    while (
        start_diff < min(len(original_lines), len(new_lines))
        and original_lines[start_diff] == new_lines[start_diff]
    ):
        start_diff += 1

    # Find last differing line (from the end)
    end_diff_orig = len(original_lines) - 1
    end_diff_new = len(new_lines) - 1
    while (
        end_diff_orig >= 0
        and end_diff_new >= 0
        and end_diff_orig >= start_diff
        and end_diff_new >= start_diff
        and original_lines[end_diff_orig] == new_lines[end_diff_new]
    ):
        end_diff_orig -= 1
        end_diff_new -= 1

    start_context = max(0, start_diff - 1)
    end_context_orig = min(len(original_lines) - 1, end_diff_orig + 1)
    end_context_new = min(len(new_lines) - 1, end_diff_new + 1)

    start_offset = sum(len(line) for line in original_lines[:start_context])
    end_offset = sum(len(line) for line in original_lines[: end_context_orig + 1])

    minimal_new = "".join(new_lines[start_context : end_context_new + 1])
    if minimal_new.startswith("\n"):  # hacky but works
        minimal_new = minimal_new[1:]
        start_offset += 1

    return minimal_new, start_offset, end_offset


def parse_hunk(hunk: str) -> tuple[int, list[str], int, list[str]]:
    """
    Parse a single diff hunk and return the input/output line numbers and content.

    Args:
        hunk (str): The complete hunk string including header and diff lines

    Returns:
        tuple: (input_start_line, input_lines, output_start_line, output_lines)
    """
    lines = hunk.splitlines(keepends=True)
    hunk_header = lines[0]
    diff_lines = lines[2:]

    parts = hunk_header.split()
    input_range = parts[1].lstrip("-")
    output_range = parts[2].lstrip("+")

    input_parts = input_range.split(",")
    output_parts = output_range.split(",")

    input_start = int(input_parts[0])
    output_start = int(output_parts[0])

    input_lines = []
    output_lines = []

    for line in diff_lines:
        if line.startswith("-"):
            input_lines.append(line[1:])
        elif line.startswith("+"):
            output_lines.append(line[1:])
        else:
            # Context line, add to both
            input_lines.append(line[1:])
            output_lines.append(line[1:])

    # EDGE CASE -- Python STD's difflib has an off-by-one error when n=0 and the a-lines are empty
    if not input_lines:
        input_start += 1

    return input_start, input_lines, output_start, output_lines


def split_into_diff_hunks(input_content: str, output_content: str):
    """
    Split two files into diff hunks.

    Args:
        input_content (str): Content of the input file
        output_content (str): Content of the output file

    Returns:
        list: A list of tuples where each tuple contains:
              (input_start_line, input_lines, output_start_line, output_lines)
    """

    input_lines = input_content.splitlines()
    output_lines = output_content.splitlines()

    diff = list(
        difflib.unified_diff(
            input_lines,
            output_lines,
            "input",
            "output",
            n=0,
        )
    )

    hunks = []
    current_hunk_lines = []

    for line in diff:
        if line.startswith("@@"):
            if current_hunk_lines:
                hunks.append(parse_hunk("\n".join(current_hunk_lines) + "\n"))
            current_hunk_lines = [line]
        elif current_hunk_lines:
            current_hunk_lines.append(line)

    if current_hunk_lines:
        hunks.append(parse_hunk("\n".join(current_hunk_lines) + "\n"))

    return hunks


def is_large_diff_above_cursor(
    original: str, completion: str, relative_cursor_position: int
) -> bool:
    """
    Check if the completion has a large diff above the cursor position.
    A large diff is defined as >5 lines added with >5 lines deleted.

    NOTE(wzeng): I'm actually approximating this as the AND of:
    1. there's a change before the user's current position
    2. the *entire* block (including after the cursor) has at least 5 lines added and 5 lines deleted

    Args:
        original: The original code block
        completion: The completed code block after changes
        relative_cursor_position: The cursor position relative to the code block

    Returns:
        True if there's a large diff above the cursor, False otherwise
    """
    if original == completion:
        return False

    # Get the content above the cursor for both original and completion
    original_above_cursor = original[:relative_cursor_position]
    completion_above_cursor = completion[:relative_cursor_position]

    if original_above_cursor == completion_above_cursor:
        # There's no change above the cursor
        return False

    diff = list(
        difflib.unified_diff(
            original.splitlines(keepends=True),
            completion.splitlines(keepends=True),
            n=0,
        )
    )

    additions = 0
    deletions = 0

    for line in diff:
        if line.startswith("+++") or line.startswith("---") or line.startswith("@@"):
            continue
        elif line.startswith("+"):
            additions += 1
        elif line.startswith("-"):
            deletions += 1
    is_large = additions > 5 and deletions > 5
    if is_large:
        logger.debug(
            f"Large diff above cursor detected: {additions} additions, {deletions} deletions"
        )

    return is_large


def is_completion_max_tokens(completion: str, model: str = "qwen") -> bool:
    """
    Check if the completion length equals max_tokens using the appropriate tokenizer.

    Args:
        completion: The completion text to check
        model: The model name to use for tokenization (defaults to "qwen")

    Returns:
        True if completion length equals AUTOCOMPLETE_OUTPUT_MAX_TOKENS, False otherwise
    """
    try:
        token_count = int(len(completion) / CHARS_PER_TOKEN)
        return token_count >= AUTOCOMPLETE_OUTPUT_MAX_TOKENS
    except Exception as e:
        logger.warning(f"Failed to count tokens for completion: {e}")
        return False


# this is fairly well tested
def detect_and_revert_end_deletion(original: str, completion: str) -> tuple[str, bool]:
    """
    Detect if completion has a large deletion from the end and revert it.
    Uses suffix anchor approach to handle cases where completion has modifications but also end deletions.
    """

    # Only consider cases where completion is significantly shorter (potential deletion)
    if len(completion) >= len(original) * 0.8:  # Less than 20% deleted
        return completion, False

    # Only run end deletion detection if completion length equals max_tokens, after length check as this costs some ms
    if not is_completion_max_tokens(completion):
        return completion, False

    # Find a substantial suffix from completion that appears uniquely in original
    MIN_SUFFIX_LENGTH = 50  # Increased for better uniqueness
    MAX_SUFFIX_LENGTH = min(
        200, len(completion) // 2
    )  # Don't use more than half the completion

    for suffix_len in range(MIN_SUFFIX_LENGTH, MAX_SUFFIX_LENGTH + 1):
        if suffix_len > len(completion):
            break

        suffix = completion[-suffix_len:]

        # Must be unique in original
        if original.count(suffix) != 1:
            continue

        suffix_pos = original.find(suffix)

        # Only consider if there's substantial content after the suffix in original
        potential_deletion = original[suffix_pos + suffix_len :]
        if len(potential_deletion) < 50:  # Not a substantial deletion
            continue

        # Check if this looks like a real end deletion (multiple lines or significant content)
        if potential_deletion.count("\n") >= 2 or len(potential_deletion) > 100:
            logger.warning(f"Detected end deletion, adding back {potential_deletion}")
            return completion + potential_deletion, True

    return completion, True


def truncate_long_lines(content: str) -> str:
    """
    Truncate lines that exceed max_line_length while preserving structure.

    Args:
        content: The content to process
        max_line_length: Maximum allowed line length (default: 300)

    Returns:
        Content with long lines truncated
        Mapping of truncated lines to their full contents
    """
    lines = content.splitlines(True)  # Keep line endings
    truncated_lines = []

    for original_line in lines:
        if len(original_line) > AUTOCOMPLETE_TRUNCATION_LINE_LENGTH:
            # Keep the line ending if present
            has_newline = original_line.endswith("\n")
            line_without_newline = original_line.rstrip("\n")

            # Truncate and add ellipsis
            truncated = (
                line_without_newline[:AUTOCOMPLETE_TRUNCATION_LINE_LENGTH] + "..."
            )

            # Restore newline if it was there
            if has_newline:
                truncated += "\n"

            truncated_lines.append(truncated)
        else:
            truncated_lines.append(original_line)

    return "".join(truncated_lines)


def should_disable_for_code_block(code_block: str) -> bool:
    """
    Check if the code block should be disabled due to long lines, this uses a larger threshold.
    """
    lines = code_block.splitlines()
    return any(len(line) > AUTOCOMPLETE_MAXIMUM_LINE_LENGTH for line in lines)


def normalize_newlines(text: str) -> str:
    """
    Normalize consecutive newlines in a string by collapsing multiple newlines into single newlines.
    This makes string comparisons newline-agnostic, so that "a\\n\\nb" is considered equal to "a\\nb".

    Args:
        text: The text to normalize

    Returns:
        Text with consecutive newlines collapsed to single newlines

    Examples:
        >>> normalize_newlines("a\\n\\nb")
        'a\\nb'
        >>> normalize_newlines("a\\n\\n\\nb")
        'a\\nb'
        >>> normalize_newlines("hello\\n\\nworld\\n\\ntest")
        'hello\\nworld\\ntest'
    """
    import re

    # Replace multiple consecutive newlines with a single newline
    return re.sub(r"\n+", "\n", text)


def is_equal_ignoring_newlines(text1: str, text2: str) -> bool:
    """
    Compare two strings for equality while ignoring differences in consecutive newlines.
    This treats "a\\n\\nb" as equal to "a\\nb".

    Args:
        text1: First string to compare
        text2: Second string to compare

    Returns:
        True if the strings are equal after normalizing newlines, False otherwise

    Examples:
        >>> is_equal_ignoring_newlines("a\\n\\nb", "a\\nb")
        True
        >>> is_equal_ignoring_newlines("hello\\nworld", "hello\\n\\nworld")
        True
        >>> is_equal_ignoring_newlines("hello\\nworld", "hello world")
        False
    """
    return normalize_newlines(text1) == normalize_newlines(text2)
