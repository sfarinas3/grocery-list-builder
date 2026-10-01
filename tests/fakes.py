"""A stand-in for `LocalLLM`, so pipeline logic can be tested without a model."""


class FakeLLM:
    """Returns canned answers in order, and records every prompt it was asked."""

    def __init__(self, *answers: dict):
        self.answers = list(answers)
        self.prompts: list[str] = []

    def ask(self, prompt: str, schema: dict, *, system: str = "", max_tokens: int = 1024) -> dict:
        self.prompts.append(prompt)
        return self.answers.pop(0) if self.answers else {}
