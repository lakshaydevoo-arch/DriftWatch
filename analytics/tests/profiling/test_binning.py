import numpy as np
import pytest

from driftwatch_analytics.profiling import assign_bins, bin_counts, quantile_edges


def test_uniform_data_gives_equal_frequency_bins() -> None:
    values = np.arange(1, 1001, dtype=np.float64)

    edges = quantile_edges(values, 10)

    assert edges.size == 9
    assert bin_counts(values, edges).tolist() == [100] * 10


def test_bins_are_right_closed_with_open_outer_bins() -> None:
    edges = np.array([0.0, 10.0])

    bins = assign_bins(np.array([-1e9, 0.0, 0.0001, 10.0, 10.0001, 1e9]), edges)

    assert bins.tolist() == [0, 0, 1, 1, 2, 2]


def test_values_outside_reference_range_land_in_outer_bins() -> None:
    reference = np.linspace(0, 1, 101)
    edges = quantile_edges(reference, 5)

    counts = bin_counts(np.array([-5.0, 7.0]), edges)

    assert counts[0] == 1 and counts[-1] == 1 and counts.sum() == 2


def test_ties_merge_duplicate_edges() -> None:
    # 90% zeros: most decile edges are 0, so they collapse into one.
    values = np.array([0.0] * 90 + list(range(1, 11)), dtype=np.float64)

    edges = quantile_edges(values, 10)

    assert np.all(np.diff(edges) > 0)
    assert edges.size < 9
    assert bin_counts(values, edges).sum() == values.size


def test_constant_values_give_single_edge() -> None:
    edges = quantile_edges(np.full(50, 3.0), 10)

    assert edges.tolist() == [3.0]
    assert bin_counts(np.full(50, 3.0), edges).tolist() == [50, 0]


def test_counts_cover_all_bins_even_when_empty() -> None:
    counts = bin_counts(np.array([5.0]), np.array([1.0, 2.0, 3.0]))

    assert counts.tolist() == [0, 0, 0, 1]


@pytest.mark.parametrize(
    ("values", "n_bins", "message"),
    [
        (np.array([1.0, 2.0]), 1, "n_bins"),
        (np.array([], dtype=np.float64), 10, "empty"),
        (np.array([1.0, np.nan]), 10, "finite"),
        (np.array([1.0, np.inf]), 10, "finite"),
    ],
)
def test_invalid_inputs_are_rejected(values: np.ndarray, n_bins: int, message: str) -> None:
    with pytest.raises(ValueError, match=message):
        quantile_edges(values, n_bins)
