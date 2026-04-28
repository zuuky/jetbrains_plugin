import argparse
import uvicorn


def main():
    parser = argparse.ArgumentParser(description="Sweep Autocomplete Server")
    parser.add_argument(
        "--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)"
    )
    parser.add_argument(
        "--port", type=int, default=8081, help="Bind port (default: 8081)"
    )
    args = parser.parse_args()

    uvicorn.run("sweep_autocomplete.app:app", host=args.host, port=args.port)


if __name__ == "__main__":
    main()
