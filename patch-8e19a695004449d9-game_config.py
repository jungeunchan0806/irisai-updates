"""Adapt Iris gameplay configuration reads to Pyla's invalidated TOML cache."""
from utils import load_toml_as_dict


def get_config(path, key, default=None):
    return load_toml_as_dict(path).get(key, default)
