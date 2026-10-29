# -*- coding: utf-8 -*-
"""ASCII entry for the read-only device probe."""
import os
import runpy
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.argv = ["实测_连表.py"]
runpy.run_path(os.path.join(HERE, "实测_连表.py"), run_name="__main__")
