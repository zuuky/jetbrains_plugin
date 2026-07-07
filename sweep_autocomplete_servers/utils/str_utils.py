from typing import Callable, Iterable, Union

from loguru import logger


def pack_items_for_prompt(
    iterable: Iterable,
    string_function: Union[callable, None],
    token_limit: int,
    char_token_ratio: int = 3.5,
    truncate_from_end: bool = True,
) -> list:
    """
    Packs items from an iterable into a list of strings, using a string function to convert each item to a string.
    The total number of tokens in the packed items will not exceed the token limit.
    Truncates from the end if truncate_from_end is True, otherwise from the beginning.
    """
    char_limit = token_limit * char_token_ratio
    packed_items = []
    current_str = ""
    if truncate_from_end:
        for item in iterable:
            item_str = string_function(item) if string_function else str(item)
            if len(current_str) + len(item_str) <= char_limit:
                packed_items.append(item)
                current_str += item_str
            else:
                break
    else:
        for item in reversed(iterable):
            item_str = string_function(item) if string_function else str(item)
            if len(current_str) + len(item_str) <= char_limit:
                packed_items.insert(0, item)
                current_str = item_str + current_str
            else:
                break
    logger.info(
        f"Removed {len(iterable) - len(packed_items)} items to fit within the token limit ({len(packed_items)} items remaining). Final token estimate: {int(len(current_str) // char_token_ratio)}"
    )
    return packed_items
