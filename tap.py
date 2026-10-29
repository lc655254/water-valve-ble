# -*- coding: utf-8 -*-
"""ASCII entry for 开关水.py (bats keep ASCII-only to dodge codepage issues)."""
import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv = ["开关水.py"] + sys.argv[1:]
runpy.run_path(os.path.join(HERE, "开关水.py"), run_name="__main__")
