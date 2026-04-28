import time
from contextlib import contextmanager
from dataclasses import dataclass, field

from loguru import logger


@dataclass
class Timer:
    name: str = ""
    min_time: float = 0.01
    start: float = 0
    end: float = 0
    time_elapsed: float = -1
    do_print: bool = True
    precision: int = 2
    steps: list[tuple[str, float]] = field(default_factory=list)
    max_expected_time: float = float("inf")

    def __enter__(self):
        self.start = time.time()
        return self

    def print(self, /, name: str | None = None, time_elapsed: float | None = None):
        if time_elapsed is None:
            time_elapsed = self.time_elapsed
        if name is None:
            name = self.name
        if time_elapsed > self.max_expected_time:
            log = logger.warning
        else:
            log = logger.debug
        if name:
            log(f"Timer {name} elapsed: {time_elapsed:.{self.precision}f} seconds")
        else:
            log(f"Timer elapsed: {time_elapsed:.{self.precision}f} seconds")

    @contextmanager
    def step(self, name: str):
        start = time.time()
        with Timer(name=name, do_print=False) as timer:
            yield timer
        end = time.time()
        time_elapsed = end - start
        if self.do_print and time_elapsed > self.min_time:
            self.print(name=name, time_elapsed=time_elapsed)
        self.steps.append((name, time_elapsed))

    def __exit__(self, exc_type, exc_value, traceback):
        self.end = time.time()
        self.time_elapsed = self.end - self.start
        if self.steps:
            logger.debug(
                f"Breakdown of {self.name} ({self.time_elapsed:.{self.precision}f} seconds):"
            )
            for name, time_elapsed in self.steps:
                logger.debug(f"  {name}: {time_elapsed:.{self.precision}f} seconds")
        elif self.do_print and self.time_elapsed > self.min_time:
            self.print()
