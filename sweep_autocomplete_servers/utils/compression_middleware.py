import gzip

import brotli
from starlette.types import ASGIApp, Receive, Scope, Send

from loguru import logger


class RequestCompressionMiddleware:
    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        content_encoding = (
            headers.get(b"content-encoding", b"").decode("latin1").lower()
        )
        if "gzip" not in content_encoding and "br" not in content_encoding:
            await self.app(scope, receive, send)
            return

        async def receive_with_decompression():
            message = await receive()

            if message["type"] == "http.request":
                body = message.get("body", b"")
                more_body = message.get("more_body", False)

                body_parts = [body] if body else []

                while more_body:
                    current_message = await receive()
                    body_part = current_message.get("body", b"")
                    if body_part:
                        body_parts.append(body_part)
                    more_body = current_message.get("more_body", False)

                full_body = b"".join(body_parts)

                if full_body:
                    try:
                        if "gzip" in content_encoding:
                            decompressed_body = gzip.decompress(full_body)
                        elif "br" in content_encoding:
                            decompressed_body = brotli.decompress(full_body)
                        else:
                            decompressed_body = full_body
                    except Exception as e:
                        logger.error(f"Decompression failed: {str(e)}")
                        raise

                    message["body"] = decompressed_body

                    new_headers = []
                    for name, value in scope.get("headers", []):
                        if name.lower() == b"content-length":
                            new_headers.append(
                                (name, str(len(decompressed_body)).encode())
                            )
                        elif name.lower() == b"content-encoding":
                            continue
                        else:
                            new_headers.append((name, value))

                    scope["headers"] = new_headers
                    message["more_body"] = False

            return message

        await self.app(scope, receive_with_decompression, send)
