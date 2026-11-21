"""API-level response models shared by endpoints."""

from pydantic import BaseModel, ConfigDict

from driftwatch_analytics.schema.validation import IssueCode, Severity, ValidationIssue


class IssueOut(BaseModel):
    """A validation issue as returned over HTTP, including its severity."""

    model_config = ConfigDict(frozen=True)

    code: IssueCode
    severity: Severity
    message: str
    column: str | None = None

    @classmethod
    def from_issue(cls, issue: ValidationIssue) -> "IssueOut":
        return cls(
            code=issue.code, severity=issue.severity, message=issue.message, column=issue.column
        )
