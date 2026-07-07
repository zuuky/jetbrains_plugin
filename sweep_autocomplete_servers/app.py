import json
import time
import traceback

from fastapi import Body
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from sweep_autocomplete.autocomplete.next_edit_autocomplete import (
    AutocompleteMetadata,
    fetch_next_edits,
)
from sweep_autocomplete.dataclasses.file_chunk_data import (
    EditorDiagnostic,
    FileChunkData,
    UserAction,
)
from sweep_autocomplete.utils.compression_middleware import RequestCompressionMiddleware
from loguru import logger

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.add_middleware(RequestCompressionMiddleware)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/backend/next_edit_autocomplete", include_in_schema=False)
def next_edit_autocomplete(
    file_path: str = Body(...),
    file_contents: str = Body(...),
    original_file_contents: str = Body(None),
    recent_changes: str = Body(...),
    cursor_position: int = Body(...),
    file_chunks: list[FileChunkData] = Body([]),
    retrieval_chunks: list[FileChunkData] = Body([]),
    recent_user_actions: list[UserAction] = Body([]),
    multiple_suggestions: bool = Body(False),
    recent_changes_high_res: str = Body(default=""),
    changes_above_cursor: bool = Body(default=True),
    editor_diagnostics: list[EditorDiagnostic] = Body(default=[]),
):
    function_start_time = time.time()

    def stream():
        metadata: AutocompleteMetadata = AutocompleteMetadata()

        try:
            for result, completions, formatted_prompt, metadata in fetch_next_edits(
                file_path=file_path,
                file_contents=file_contents,
                recent_changes=recent_changes,
                cursor_position=cursor_position,
                original_file_contents=original_file_contents,
                file_chunks=file_chunks,
                retrieval_chunks=retrieval_chunks,
                recent_user_actions=recent_user_actions,
                recent_changes_high_res=recent_changes_high_res,
                changes_above_cursor=changes_above_cursor,
                is_new_user=False,
                editor_diagnostics=editor_diagnostics,
            ):
                data = {
                    **result.__dict__,
                    "elapsed_time_ms": int((time.time() - function_start_time) * 1000),
                }
                logger.debug(
                    f"Next edit autocomplete took {data['elapsed_time_ms']}ms"
                )

                if multiple_suggestions:
                    data["completions"] = [
                        completion.__dict__ for completion in completions
                    ]
                yield json.dumps(data) + "\n"

        except BaseException as e:
            logger.error(f"Next edit autocomplete error: {str(e)}")
            yield json.dumps(
                {
                    "status": "error",
                    "error": f"Next edit autocomplete error: {str(e)}",
                    "traceback": str(traceback.format_exc()),
                }
            )
            if not isinstance(e, GeneratorExit):
                raise e
        finally:
            end_time = time.time()
            latency_ms = (end_time - function_start_time) * 1000
            logger.debug(
                f"Next edit autocomplete took {latency_ms:.2f}ms for finally block:{metadata.convert_to_string()}"
            )

    return StreamingResponse(stream(), media_type="application/x-ndjson")
