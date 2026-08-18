import argparse
import uvicorn

from sweep_autocomplete.app import app


def main() -> None:
    parser = argparse.ArgumentParser(description="Sweep next-edit autocomplete server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8006, help="Bind port (default: 8006)")
    parser.add_argument(
        "--workers",
        type=int,
        default=1,
        help="Uvicorn workers. Keep at 1: the model is a process-wide singleton.",
    )
    args = parser.parse_args()

    uvicorn.run(app, host=args.host, port=args.port, workers=args.workers)


if __name__ == "__main__":
    main()
