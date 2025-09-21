import inspect
import json
import sys
from collections import namedtuple

if not hasattr(inspect, "getargspec"):
    _ArgSpec = namedtuple("ArgSpec", "args varargs keywords defaults")

    def _getargspec(func):
        spec = inspect.getfullargspec(func)
        return _ArgSpec(spec.args, spec.varargs, spec.varkw, spec.defaults)

    inspect.getargspec = _getargspec  # type: ignore[attr-defined]

from py_tat_morphan import __version__
from py_tat_morphan.morphan import Morphan

_morphan = Morphan()

def _analyze_token(token: str) -> dict:
    token = token or ""
    tag = _morphan.analyse(token)
    return {
        "token": token,
        "tag": tag,
        "morphan_version": __version__,
        "format": 1,
    }


def _analyze_text(text: str) -> dict:
    text = text or ""
    tokens_count, unique_tokens_count, sentences_count, sentences = _morphan.analyse_text(text)
    return {
        "tokens_count": tokens_count,
        "unique_tokens_count": unique_tokens_count,
        "sentenes_count": sentences_count,
        "sentences": sentences,
        "morphan_version": __version__,
        "format": 1,
    }


def _dispatch(request: dict) -> dict:
    command = request.get("cmd")
    if command == "version":
        return {"version": __version__}
    if command == "token":
        return _analyze_token(request.get("token", ""))
    if command == "text":
        return _analyze_text(request.get("text", ""))
    if command == "shutdown":
        return {"status": "stopped"}
    return {
        "error": "unknown_command",
        "message": f"Unsupported command: {command}",
    }


def main() -> None:
    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            response = {"error": "invalid_json", "message": "Unable to decode request"}
        else:
            response = _dispatch(request)
            if request.get("cmd") == "shutdown":
                print(json.dumps(response, ensure_ascii=False), flush=True)
                break
        print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
