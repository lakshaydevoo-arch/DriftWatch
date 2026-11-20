import pandas as pd
import pytest

from driftwatch_analytics.schema import CsvFormatError, read_csv_text


def test_reads_all_cells_as_strings() -> None:
    df = read_csv_text(b"a,b\n1,x\n2.5,y\n")

    assert list(df.columns) == ["a", "b"]
    assert df["a"].tolist() == ["1", "2.5"]
    assert all(pd.api.types.is_string_dtype(df[c]) for c in df.columns)


def test_empty_cells_are_kept_as_empty_strings() -> None:
    df = read_csv_text(b"a,b\n1,\n,x\n")

    assert df["a"].tolist() == ["1", ""]
    assert df["b"].tolist() == ["", "x"]


def test_quoted_fields_with_commas_and_newlines() -> None:
    df = read_csv_text(b'a,b\n"x, y","line1\nline2"\n')

    assert df["a"].tolist() == ["x, y"]
    assert df["b"].tolist() == ["line1\nline2"]


def test_utf8_bom_and_crlf_line_endings_are_accepted() -> None:
    df = read_csv_text("﻿city,n\r\nZürich,1\r\n".encode())

    assert list(df.columns) == ["city", "n"]
    assert df["city"].tolist() == ["Zürich"]


def test_header_only_file_gives_zero_rows() -> None:
    df = read_csv_text(b"a,b\n")

    assert list(df.columns) == ["a", "b"]
    assert len(df) == 0


@pytest.mark.parametrize(
    ("data", "message"),
    [
        (b"", "empty"),
        (b"a,b\n1,2\n3\n", "line 3: expected 2 fields, found 1"),
        (b"a,b\n1,2,3\n", "line 2: expected 2 fields, found 3"),
        (b"a,a\n1,2\n", "duplicate column names: \\['a'\\]"),
        (b"a,,c\n1,2,3\n", "blank column name at position\\(s\\) \\[2\\]"),
        (b"a,b\n\xff\xfe,1\n", "not valid UTF-8"),
        (b'a,b\n"unterminated,1\n', "malformed CSV"),
    ],
)
def test_malformed_input_is_rejected(data: bytes, message: str) -> None:
    with pytest.raises(CsvFormatError, match=message):
        read_csv_text(data)
