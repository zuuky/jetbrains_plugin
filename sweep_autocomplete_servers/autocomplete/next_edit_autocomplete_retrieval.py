from __future__ import annotations

import difflib
import re
from functools import lru_cache


from sweep_autocomplete.autocomplete.next_edit_autocomplete_utils import (
    extract_diff_parts,
    parse_hunk,
    split_into_hunks,
    get_line_number_from_position,
)
from sweep_autocomplete.dataclasses.file_chunk_data import EditorDiagnostic
from loguru import logger
from sweep_autocomplete.utils.timer import Timer

# Precompile regex for better performance with case-insensitive flag
_WORD_PATTERN = re.compile(r"\w+", re.IGNORECASE)


@lru_cache(maxsize=2048)
def simple_tokenizer(text):
    """Cached tokenizer that extracts words (case-insensitive)."""
    # Use case-insensitive regex to avoid .lower() call
    tokens = _WORD_PATTERN.findall(text)
    return [token for token in tokens]


@lru_cache(maxsize=2048)
def simple_tokenizer_with_offsets(text) -> list[tuple[str, int, int]]:
    """Cached tokenizer that extracts words with their character offsets (case-insensitive).

    Returns:
        List of tuples (token, start_offset, end_offset)
    """
    # Use finditer to get match objects with position information
    tokens_with_offsets = [
        (match.group(), match.start(), match.end())
        for match in _WORD_PATTERN.finditer(text)
    ]
    return tokens_with_offsets


def extract_added_and_deleted_code_from_recent_changes(
    recent_changes: str, file_tokens_set: set[str]
) -> tuple[list[str], list[str]]:
    """
    Extracts the deleted code section from the recent_changes diff.
    Returns the concatenated words that were changed (deleted or added) at the word level.
    If no word-level changes exist, returns the largest deleted or added code block.
    """
    hunks = split_into_hunks(recent_changes)
    hunks = [hunk for hunk in hunks if len(hunk.strip().splitlines()) > 1]
    if not hunks:
        return [], []
    added_words = []
    deleted_words = []
    for hunk in hunks[::-1]:
        first_hunk_line = hunk.splitlines()[0]
        if "." not in first_hunk_line:
            extension = ""
        else:
            extension = first_hunk_line.split(".")[1].lower()
        added_words, deleted_words = extract_added_and_deleted_from_hunk(
            hunk, extension=extension
        )
        deleted_words = [
            word for word in deleted_words if len(word) > 1 and word in file_tokens_set
        ]
        if len(deleted_words) == 1:
            return added_words, deleted_words
    return added_words, deleted_words


def extract_added_and_deleted_from_hunk(
    hunk: str, extension: str
) -> tuple[list[str], list[str]]:
    old_code, new_code = extract_diff_parts(
        "".join(line for line in hunk.splitlines(True) if not line.startswith("File: "))
    )
    # Split on word boundaries while preserving whitespace and punctuation
    old_words = re.findall(r"\w+|\s+|[^\w\s]", old_code)
    new_words = re.findall(r"\w+|\s+|[^\w\s]", new_code)
    sm = difflib.SequenceMatcher(None, old_words, new_words)
    original_deleted_words = []
    original_added_words = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag in ("replace", "delete"):
            original_deleted_words.extend(old_words[i1:i2])
        if tag in ("replace", "insert"):
            original_added_words.extend(new_words[j1:j2])
    added_words = [word for word in original_added_words if len(word) > 1]
    deleted_words = [word for word in original_deleted_words if len(word) > 1]
    added_words = list(set(added_words))
    deleted_words = list(set(deleted_words))
    logger.info(
        f"Original added words: {original_added_words}, added words: {added_words}"
    )
    logger.info(
        f"Original deleted words: {original_deleted_words}, deleted words: {deleted_words}"
    )
    return added_words, deleted_words


def extract_deleted_lines_from_recent_changes(
    recent_changes: str,
) -> list[tuple[str, int]]:
    """
    Extracts the full deleted lines from the recent_changes diff with their line numbers.
    Returns a list of tuples (deleted_line, line_number) where deleted_line is stripped of
    leading '-' and whitespace, and line_number is the original line number in the file.
    """
    hunks = split_into_hunks(recent_changes)
    deleted_lines_with_numbers = []

    for hunk in hunks:
        # Skip hunks that don't have @@ markers (e.g., just file headers)
        if "@@" not in hunk:
            continue

        # Parse the hunk to get line numbers
        first_line, *rest = hunk.splitlines(True)
        diff_hunk = "".join(rest)
        input_start_line, input_lines, output_start_line, output_lines = parse_hunk(
            diff_hunk
        )

        # Track current line number in the input (original file)
        current_line = input_start_line

        for line in input_lines:
            # Check if this is a deleted line (exists in input but not in output)
            line_stripped = line.strip()
            if line_stripped and line not in output_lines:
                deleted_lines_with_numbers.append((line_stripped, current_line))
            current_line += 1

    return deleted_lines_with_numbers


def find_deleted_line_match(
    file_contents: str, deleted_lines: list[tuple[str, int]]
) -> tuple[str, int] | None:
    """
    Searches for deleted lines in the file contents and returns a code block around the match.
    Avoids matching the same line that was deleted (based on line number).

    Args:
        file_contents: The current file contents to search in
        deleted_lines: List of tuples (deleted_line, original_line_number) from recent changes

    Returns:
        Tuple of (retrieved_code_block, block_start_offset) if a match is found, None otherwise.
        The code block includes 3 lines above and 3 lines below the matched line.
    """
    file_lines = file_contents.splitlines(keepends=True)

    for deleted_line, original_line_number in deleted_lines:
        # Extract distinct terms from the deleted line
        line_tokens = simple_tokenizer(deleted_line)
        distinct_terms = set(line_tokens)

        # Check if the line has at least 3 distinct terms
        if len(distinct_terms) >= 3:
            # Check if this exact line exists in the file contents
            for line_index, file_line in enumerate(file_lines):
                # Skip if this is the same line that was deleted
                if line_index == original_line_number:
                    continue

                if deleted_line in file_line.strip():
                    start_line = line_index
                    end_line = min(
                        len(file_lines), line_index + 4
                    )  # +4 to include matched line + 3 below
                    retrieved_code_block = "".join(file_lines[start_line:end_line])
                    logger.info(
                        f"Retrieved code block from deleted line match: {retrieved_code_block}"
                    )

                    # Convert line_index to offset
                    block_start_offset = sum(
                        len(file_lines[j]) for j in range(line_index)
                    )
                    return retrieved_code_block, block_start_offset
    return None


def find_best_matching_block(
    file_contents: str,
    recent_changes: str,
    cursor_position: int,
    block_size: int = 6,
    editor_diagnostics: list[EditorDiagnostic] = None,
) -> tuple[str, int, bool, EditorDiagnostic | None]:
    # Extract deleted lines
    with Timer(
        min_time=0.001, precision=3, name="extract_deleted_lines_from_recent_changes"
    ):
        # Extract deleted lines from recent_changes
        deleted_lines = extract_deleted_lines_from_recent_changes(recent_changes)
        # Check if any deleted line is long enough (3+ distinct terms) and exists elsewhere in the file
        # This takes precedence over the single-word heuristic
        deleted_line_match = find_deleted_line_match(file_contents, deleted_lines)
        if deleted_line_match is not None:
            retrieved_code_block, block_start_offset = deleted_line_match
            return retrieved_code_block, block_start_offset, False, None

    # simple filter for query tokens
    # takes 5ms for 4k lines
    with Timer(min_time=0.001, precision=3, name="simple_tokenizer_with_offsets"):
        file_tokens_with_offsets: list[tuple[str, int, int]] = (
            simple_tokenizer_with_offsets(file_contents)
        )
        file_tokens = [token for token, _, _ in file_tokens_with_offsets]

    with Timer(
        min_time=0.001,
        precision=3,
        name="extract_added_and_deleted_code_from_recent_changes",
    ):
        added_words, deleted_words = extract_added_and_deleted_code_from_recent_changes(
            recent_changes, set(file_tokens)
        )

    current_cursor_line_number = get_line_number_from_position(
        file_contents, cursor_position
    )

    # 1. if deleted words is of length 1, use that word to determine direction
    # 2. if added words exist, for each keyword check if it's a good query term
    # - a good query term is one that appears in the file_contents >= 1
    # - it also can't occur too many times. something like 3-5 times at most depending on line count
    if len(deleted_words) == 1:
        logger.info(f"Retrieved deleted word: {deleted_words[0]}")
        query_token = deleted_words[0]
    else:
        for word in added_words:
            query_token_line_numbers = [
                get_line_number_from_position(file_contents, offset)
                for token, offset, _ in file_tokens_with_offsets
                if token == word
            ]
            if (
                word in file_tokens
                and 5 >= file_tokens.count(word) > 1
                and any(
                    abs(line_number - current_cursor_line_number) > 10
                    for line_number in query_token_line_numbers
                )
            ):
                query_token = word
                break
        else:
            query_token = None

    # find the closest match in file_tokens
    # get all indices in file_contents which match the query_token
    query_token_offsets = [
        offset
        for token, offset, _ in file_tokens_with_offsets
        if token == query_token
        and abs(
            get_line_number_from_position(file_contents, offset)
            - current_cursor_line_number
        )
        > 10
    ]

    closest_error = None
    if editor_diagnostics:
        # if any are errors, we can use the closest diagnostic as the offset. this can take priority over query_token
        # filter to the closest diagnostic that's not within 10 lines of cursor (in line numbers)
        filtered_error_diagnostics = [
            diagnostic
            for diagnostic in editor_diagnostics
            if diagnostic.severity == "ERROR"
            and abs(current_cursor_line_number - diagnostic.line_number) > 10
        ]
        if filtered_error_diagnostics:
            closest_error = min(
                filtered_error_diagnostics,
                key=lambda x: abs(cursor_position - x.start_offset),
            )

    if closest_error:
        # get a block using closest_error.start_offset
        lines = file_contents.splitlines(keepends=True)
        cumulative_offset = 0
        start_line = 0
        for i, line in enumerate(lines):
            if cumulative_offset + len(line) > closest_error.start_offset:
                start_line = i
                break
            cumulative_offset += len(line)
        end_line = min(len(lines), start_line + 1)
        retrieved_code_block = "".join(lines[start_line:end_line])
        block_start_offset = sum(len(lines[i]) for i in range(start_line))
        return retrieved_code_block, block_start_offset, False, closest_error
    elif query_token_offsets:
        closest_offset = min(
            query_token_offsets, key=lambda x: abs(cursor_position - x)
        )

        # takes less than 1ms for 4k lines
        with Timer(min_time=0.001, precision=3, name="find_line_containing_offset"):
            # Find the line containing closest_offset
            lines = file_contents.splitlines(keepends=True)
            cumulative_offset = 0
            line_index = 0
            for i, line in enumerate(lines):
                if cumulative_offset + len(line) > closest_offset:
                    line_index = i
                    break
                cumulative_offset += len(line)

        end_line = min(len(lines), line_index + 1)
        retrieved_code_block = "".join(lines[line_index:end_line])
        logger.info(f"Retrieved code block: {retrieved_code_block}")
        # convert line_index to offset
        block_start_offset = sum(len(lines[i]) for i in range(line_index))
        return retrieved_code_block, block_start_offset, False, closest_error
    else:
        # Go down 6 lines from cursor - this is the "block right after current block" case
        suffix = file_contents[cursor_position:]
        suffix_start = suffix.find("\n")
        if suffix_start != -1:
            suffix = suffix[suffix_start + 1 :]
        lines = suffix.splitlines(keepends=True)
        cursor_position += suffix_start + 1 + len("".join(lines[:block_size]))
        lines = lines[block_size:]
        fallback_block = "".join(lines[:block_size])

        logger.debug(
            f"[SIMPLIFIED] Using block after cursor, block_size={len(fallback_block)}chars"
        )
        return (
            fallback_block,
            cursor_position,
            True,
            None,
        )  # True indicates block after cursor
