# -*- coding: utf-8 -*-
"""ASCII entry for the startup benchmark."""
import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv = ["_bench_启动.py"]
runpy.run_path(os.path.join(HERE, "_bench_启动.py"), run_name="__main__")
