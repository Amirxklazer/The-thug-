import contextlib
import io
import os
import traceback


def run(code, cwd):
    """Run AI-written Python inside the sandbox folder and return its output."""
    os.chdir(cwd)
    out = io.StringIO()
    g = {"__name__": "__main__"}
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            exec(compile(code, "<14ai>", "exec"), g)
    except BaseException:
        out.write(traceback.format_exc())
    return out.getvalue()[-6000:]
