class HarnessCancelled(RuntimeError):
    """Raised when a caller cancels an in-flight harness turn."""


class RunLimitExceeded(RuntimeError):
    def __init__(self, reason_code: str) -> None:
        self.reason_code = reason_code
        super().__init__(reason_code)


class ResearchSystemError(RuntimeError):
    def __init__(self, reason_code: str, message: str = "") -> None:
        self.reason_code = reason_code
        super().__init__(message or reason_code)
