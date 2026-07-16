import builtins
import os
import sys
import traceback


class _TerminalStream:
    def __init__(self, console, stderr=False):
        self.console = console
        self.stderr = stderr

    def write(self, value):
        text = str(value)
        if self.stderr:
            self.console.writeStderr(text)
        else:
            self.console.writeStdout(text)
        return len(text)

    def flush(self):
        return None

    def isatty(self):
        return True


def _clear_project_modules(project_directory):
    prefix = os.path.realpath(project_directory) + os.sep
    for name, module in list(sys.modules.items()):
        module_file = getattr(module, "__file__", None)
        if module_file and os.path.realpath(module_file).startswith(prefix):
            sys.modules.pop(name, None)


def run_code(code, file_name, project_directory, console, hardware):
    """Run one active file while making sibling project files importable."""
    os.makedirs(project_directory, exist_ok=True)
    source_path = os.path.join(project_directory, file_name)
    previous_cwd = os.getcwd()
    previous_path = list(sys.path)
    previous_stdout = sys.stdout
    previous_stderr = sys.stderr
    previous_input = builtins.input
    _clear_project_modules(project_directory)

    def terminal_input(prompt=""):
        return console.readLine(str(prompt))

    try:
        os.chdir(project_directory)
        sys.path.insert(0, project_directory)
        sys.stdout = _TerminalStream(console)
        sys.stderr = _TerminalStream(console, stderr=True)
        builtins.input = terminal_input
        namespace = {
            "__name__": "__main__",
            "__file__": source_path,
            "__package__": None,
            "hardware": hardware,
        }
        compiled = compile(code, source_path, "exec")
        exec(compiled, namespace, namespace)
        return True, "", -1, ""
    except BaseException as error:
        formatted = "".join(traceback.TracebackException.from_exception(error).format())
        line = -1
        extracted = traceback.extract_tb(error.__traceback__)
        for frame in reversed(extracted):
            if os.path.realpath(frame.filename) == os.path.realpath(source_path):
                line = frame.lineno
                break
        if isinstance(error, SyntaxError) and error.lineno:
            line = error.lineno
        return False, formatted, line, type(error).__name__
    finally:
        builtins.input = previous_input
        sys.stdout = previous_stdout
        sys.stderr = previous_stderr
        sys.path[:] = previous_path
        os.chdir(previous_cwd)

