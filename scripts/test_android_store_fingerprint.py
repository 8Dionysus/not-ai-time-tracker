"""Synthetic SQLite fixtures and an in-process adb transport fake; never contacts a device."""
import contextlib
import io
import json
import pathlib
import runpy
import shlex
import sqlite3
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

SCRIPT = pathlib.Path(__file__).with_name("android_store_fingerprint.py")


def database(version, confirmation=None):
    connection = sqlite3.connect(":memory:")
    connection.execute(f"PRAGMA user_version={version}")
    for name in ("metadata", "vocabulary"):
        connection.execute(f"CREATE TABLE {name}(id INTEGER,payload TEXT)")
    for name in ("activity_types", "cards", "favorites", "measurements"):
        connection.execute(f"CREATE TABLE {name}(id TEXT,position INTEGER,payload TEXT)")
    connection.execute("CREATE TABLE store_generation(id INTEGER,generation INTEGER)")
    connection.execute("CREATE TABLE history_classifications(id TEXT,payload TEXT)")
    connection.execute("INSERT INTO history_classifications VALUES('c','{}')")
    connection.execute("CREATE TABLE sessions(id TEXT,position INTEGER,status TEXT,selectionId TEXT,note TEXT,importOrigin TEXT)")
    connection.execute("INSERT INTO sessions VALUES('session',0,'STOPPED','c',NULL,NULL)")
    extra = ",manualConfirmation TEXT" if version >= 10 else ""
    connection.execute("CREATE TABLE segments(id TEXT,position INTEGER,sessionId TEXT,openSlot INTEGER,start INTEGER,end INTEGER,durationMs INTEGER,selectionId TEXT,anchor TEXT,uncertain INTEGER,observedUntil INTEGER,limitMs INTEGER" + extra + ")")
    row = ["segment", 0, "session", None, 100, 200, 100, "c", None, 0, None, None]
    if version >= 10:
        row.append(json.dumps(confirmation) if confirmation is not None else None)
    connection.execute("INSERT INTO segments VALUES(" + ",".join("?" for _ in row) + ")", row)
    return connection


def fingerprint(connection):
    def output(command, **kwargs):
        if command[3:] == ["shell", "getprop", "ro.boot.qemu.avd_name"]:
            return "fingerprint-fixture\n"
        query = shlex.split(command[-1])[-1]
        assert query == "PRAGMA user_version;", command
        return str(connection.execute(query).fetchone()[0]) + "\n"

    def process(command, **kwargs):
        assert command[:4] == ["adb", "-s", "emulator-fixture", "exec-out"], command
        query = shlex.split(command[-1])[-1]
        rows = [row[0] for row in connection.execute(query)]
        return types.SimpleNamespace(stdout=io.StringIO("\n".join(rows) + ("\n" if rows else "")), wait=lambda: 0)

    with tempfile.TemporaryDirectory(prefix="tracker-fingerprint-fixture-") as directory:
        destination = pathlib.Path(directory) / "fingerprint.json"
        args = [str(SCRIPT), "--serial", "emulator-fixture", "--avd", "fingerprint-fixture", "--output", str(destination)]
        with patch.object(sys, "argv", args), patch("subprocess.check_output", output), patch("subprocess.Popen", process), contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(str(SCRIPT), run_name="__main__")
        return json.loads(destination.read_text())


class FingerprintTest(unittest.TestCase):
    def test_confirmation_change_changes_hash_without_any_other_row_difference(self):
        a = database(10, {"confirmedAt": 1000, "source": "BACKFILL"})
        b = database(10, {"confirmedAt": 1001, "source": "BACKFILL"})
        try:
            first, second = fingerprint(a), fingerprint(b)
            self.assertEqual(first["tables"]["sessions"], second["tables"]["sessions"])
            self.assertNotEqual(first["tables"]["segments"]["canonicalValuesSha256"], second["tables"]["segments"]["canonicalValuesSha256"])
        finally:
            a.close(); b.close()

    def test_absent_legacy_column_and_null_current_column_remain_comparable(self):
        values = []
        for version in (5, 9, 10, 12):
            connection = database(version)
            try:
                values.append(fingerprint(connection)["tables"]["segments"]["canonicalValuesSha256"])
            finally:
                connection.close()
        self.assertEqual(1, len(set(values)))

    def test_confirmation_json_order_is_canonical(self):
        a = database(10, {"confirmedAt": 1000, "source": "BACKFILL"})
        b = database(10, {"source": "BACKFILL", "confirmedAt": 1000})
        try:
            self.assertEqual(fingerprint(a)["tables"]["segments"], fingerprint(b)["tables"]["segments"])
        finally:
            a.close(); b.close()


if __name__ == "__main__":
    unittest.main()
