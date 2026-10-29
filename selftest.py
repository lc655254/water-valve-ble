# -*- coding: utf-8 -*-
"""ASCII entry for the offline self test."""
import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv = ["自检_离线.py"]
runpy.run_path(os.path.join(HERE, "自检_离线.py"), run_name="__main__")
