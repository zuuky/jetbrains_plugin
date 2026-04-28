from dataclasses import dataclass


@dataclass
class FileChunkData:
    file_path: str
    start_line: int
    end_line: int
    content: str

    def to_string(self) -> str:
        return f"<|file_sep|>{self.file_path}\n{self.content}\n"


@dataclass
class UserAction:
    action_type: str
    line_number: int
    offset: int
    file_path: str
    timestamp: int = 0


@dataclass
class EditorDiagnostic:
    line: int  # this is 1-indexed, use line_number to get 0-indexed
    start_offset: int
    end_offset: int
    severity: str
    message: str
    timestamp: int = 0

    @property
    def line_number(self) -> int:
        return self.line - 1
