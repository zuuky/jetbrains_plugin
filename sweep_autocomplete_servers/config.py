import os

NEXT_EDIT_AUTOCOMPLETE_ENDPOINT = os.environ.get(
    "NEXT_EDIT_AUTOCOMPLETE_ENDPOINT", None
)

MODEL_REPO = os.environ.get("MODEL_REPO", "sweepai/sweep-next-edit-0.5B")
MODEL_FILENAME = os.environ.get("MODEL_FILENAME", "sweep-next-edit-0.5b.q8_0.gguf")
