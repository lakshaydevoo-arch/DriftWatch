import math

import pytest

from driftwatch_analytics.synthetic import CategoricalFeature, LogNormalFeature, NormalFeature


def test_valid_specs_construct() -> None:
    NormalFeature("x", mean=0.0, std=1.0)
    LogNormalFeature("y", mean_log=1.0, sigma=0.3)
    CategoricalFeature("c", categories=("a", "b", "c"), probabilities=(0.1, 0.2, 0.7))


@pytest.mark.parametrize(
    ("factory", "message"),
    [
        (lambda: NormalFeature("", mean=0.0, std=1.0), "non-empty"),
        (lambda: NormalFeature("x", mean=0.0, std=0.0), "std"),
        (lambda: NormalFeature("x", mean=0.0, std=-1.0), "std"),
        (lambda: NormalFeature("x", mean=math.nan, std=1.0), "mean"),
        (lambda: NormalFeature("x", mean=0.0, std=math.inf), "std"),
        (lambda: LogNormalFeature("y", mean_log=0.0, sigma=0.0), "sigma"),
        (lambda: CategoricalFeature("c", categories=(), probabilities=()), "at least one"),
        (
            lambda: CategoricalFeature("c", categories=("a", "b"), probabilities=(1.0,)),
            "2 categories",
        ),
        (
            lambda: CategoricalFeature("c", categories=("a", "a"), probabilities=(0.5, 0.5)),
            "unique",
        ),
        (lambda: CategoricalFeature("c", categories=("a", "b"), probabilities=(1.2, -0.2)), ">= 0"),
        (
            lambda: CategoricalFeature("c", categories=("a", "b"), probabilities=(0.5, 0.4)),
            "sum to 1",
        ),
    ],
)
def test_invalid_specs_are_rejected(factory: object, message: str) -> None:
    assert callable(factory)
    with pytest.raises(ValueError, match=message):
        factory()
