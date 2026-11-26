"""Join late ground-truth labels to a batch's predictions by record id (design §6).

Rules:

- Ids are compared as exact text after trimming whitespace (``"007"`` and
  ``"7"`` are different ids). A missing id (empty, ``NA``, ...) joins nothing.
- Every non-missing id must be unique, in the batch and in the labels. A
  duplicate makes the join ambiguous, so it is an error rather than a guess.
- A batch row is *joined* when its id has a label row whose label is present
  and parses as the target's type. Matched rows with a missing or invalid
  label are counted separately.
- ``join_rate = n_joined / n_current_rows``: metrics on 5% of a batch are not
  comparable to metrics on all of it, so the rate is always reported.

Label rows for ids that are not in the batch are counted and ignored, and
columns other than the id and the target are ignored.
"""

from dataclasses import dataclass

import numpy as np
import pandas as pd
from pydantic import BaseModel, ConfigDict, Field

from driftwatch_analytics.schema import ColumnRole, ColumnSchema, DatasetSchema, parse_column
from driftwatch_analytics.schema.parsing import normalise


class JoinError(ValueError):
    """The inputs cannot be joined. ``kind`` is a problem kind for the API."""

    def __init__(self, kind: str, message: str, errors: list[dict[str, object]]) -> None:
        super().__init__(message)
        self.kind = kind
        self.errors = errors


class JoinReport(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")

    n_current_rows: int = Field(ge=0)
    n_label_rows: int = Field(ge=0)
    n_current_without_id: int = Field(ge=0, description="Batch rows with a missing id")
    n_labels_without_id: int = Field(ge=0, description="Label rows with a missing id")
    n_matched: int = Field(ge=0, description="Batch rows whose id has a label row")
    n_missing_labels: int = Field(ge=0, description="Matched, but the label is missing")
    n_invalid_labels: int = Field(ge=0, description="Matched, but the label does not parse")
    n_joined: int = Field(ge=0, description="Matched with a valid label")
    n_unmatched_labels: int = Field(ge=0, description="Label rows whose id is not in the batch")
    join_rate: float | None = Field(
        default=None, ge=0, le=1, description="n_joined / n_current_rows; null for an empty batch"
    )


@dataclass(frozen=True)
class JoinResult:
    report: JoinReport
    # One row per joined record: "record_id" and "label" (parsed), plus the
    # batch's row position ("row") so predictions can be looked up.
    joined: pd.DataFrame


def join_labels(current: pd.DataFrame, labels: pd.DataFrame, schema: DatasetSchema) -> JoinResult:
    """Join ``labels`` to ``current`` (both text frames from ``read_csv_text``).

    Raises:
        JoinError: ``labels-not-configured`` if the schema has no target or
            record id column; ``schema-mismatch`` if a needed column is absent;
            ``duplicate-record-ids`` if an id repeats.
    """
    target, record_id = _role_columns(schema)
    _require_columns(current, "current", [record_id])
    _require_columns(labels, "labels", [record_id, target.name])

    current_ids, current_missing = normalise(current[record_id])
    label_ids, label_missing = normalise(labels[record_id])
    _require_unique(current_ids[~current_missing], "current")
    _require_unique(label_ids[~label_missing], "labels")

    parsed = parse_column(
        labels[target.name], target.logical_type, integer_coded=target.integer_coded
    )
    keyed = pd.DataFrame(
        {
            "record_id": label_ids[~label_missing],
            "label": parsed.values[~label_missing],
            "label_missing": parsed.missing[~label_missing],
            "label_invalid": parsed.invalid[~label_missing],
        }
    )
    batch = pd.DataFrame(
        {"record_id": current_ids[~current_missing], "row": np.flatnonzero(~current_missing)}
    )
    matched = batch.merge(keyed, on="record_id", how="inner", validate="one_to_one")
    valid = matched[~(matched["label_missing"] | matched["label_invalid"])]

    n_rows = len(current)
    report = JoinReport(
        n_current_rows=n_rows,
        n_label_rows=len(labels),
        n_current_without_id=int(current_missing.sum()),
        n_labels_without_id=int(label_missing.sum()),
        n_matched=len(matched),
        n_missing_labels=int(matched["label_missing"].sum()),
        n_invalid_labels=int(matched["label_invalid"].sum()),
        n_joined=len(valid),
        n_unmatched_labels=len(keyed) - len(matched),
        join_rate=len(valid) / n_rows if n_rows else None,
    )
    joined = valid[["record_id", "row", "label"]].reset_index(drop=True)
    return JoinResult(report=report, joined=joined)


def _role_columns(schema: DatasetSchema) -> tuple[ColumnSchema, str]:
    targets = schema.with_role(ColumnRole.TARGET)
    record_ids = schema.with_role(ColumnRole.RECORD_ID)
    absent = [
        role
        for role, columns in (("target_column", targets), ("record_id_column", record_ids))
        if not columns
    ]
    if absent:
        raise JoinError(
            "labels-not-configured",
            f"the reference profile has no {' or '.join(absent)}; register the reference "
            "with both to join labels",
            [{"missing": role} for role in absent],
        )
    return targets[0], record_ids[0].name


def _require_columns(data: pd.DataFrame, part: str, columns: list[str]) -> None:
    absent = [c for c in columns if c not in data.columns]
    if absent:
        raise JoinError(
            "schema-mismatch",
            f"the {part} CSV is missing required columns: {absent}",
            [{"part": part, "column": c} for c in absent],
        )


def _require_unique(ids: pd.Series, part: str) -> None:
    duplicated = ids[ids.duplicated(keep=False)]
    if not duplicated.empty:
        # Counts only: request contents are never echoed back.
        raise JoinError(
            "duplicate-record-ids",
            f"record ids must be unique; {duplicated.nunique()} id(s) repeat in the {part} CSV",
            [{"part": part, "n_duplicated_ids": int(duplicated.nunique())}],
        )
