import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "validate_migration_compatibility.py"
SPEC = importlib.util.spec_from_file_location("migration_compatibility", SCRIPT)
POLICY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POLICY)


class MigrationCompatibilityPolicyTest(unittest.TestCase):
    def roots(self):
        temporary = tempfile.TemporaryDirectory()
        root = Path(temporary.name)
        base, candidate = root / "base", root / "candidate"
        for checkout in (base, candidate):
            (checkout / POLICY.MIGRATIONS).mkdir(parents=True)
            (checkout / POLICY.MANIFEST.parent).mkdir(parents=True, exist_ok=True)
            (checkout / POLICY.MANIFEST).write_text('{"schemaVersion": 1}', encoding="utf-8")
        return temporary, base, candidate

    def declare(self, candidate, version, classification="rolling-safe"):
        data = {"schemaVersion": 1, "migrations": {version: {
            "classification": classification,
            "rationale": "reviewed compatibility reasoning",
            "previousRevisionEvidence": "startup and affected operation evidence",
            "ownerReview": "database owner approval required before merge"
        }}}
        (candidate / POLICY.MANIFEST).write_text(json.dumps(data), encoding="utf-8")

    def test_accepts_new_migration_with_complete_decision(self):
        temporary, base, candidate = self.roots()
        with temporary:
            (candidate / POLICY.MIGRATIONS / "V51__example.sql").write_text("SELECT 1;", encoding="utf-8")
            self.declare(candidate, "V51")
            self.assertEqual([], POLICY.validate(base, candidate))

    def test_rejects_new_migration_without_decision(self):
        temporary, base, candidate = self.roots()
        with temporary:
            (candidate / POLICY.MIGRATIONS / "V51__example.sql").write_text("SELECT 1;", encoding="utf-8")
            self.assertTrue(any("no migrations.V51" in error for error in POLICY.validate(base, candidate)))

    def test_rejects_historical_change_or_deletion(self):
        temporary, base, candidate = self.roots()
        with temporary:
            (base / POLICY.MIGRATIONS / "V50__one.sql").write_text("SELECT 1;", encoding="utf-8")
            (base / POLICY.MIGRATIONS / "V49__two.sql").write_text("SELECT 2;", encoding="utf-8")
            (candidate / POLICY.MIGRATIONS / "V50__one.sql").write_text("SELECT 3;", encoding="utf-8")
            errors = POLICY.validate(base, candidate)
            self.assertTrue(any("changed: V50" in error for error in errors))
            self.assertTrue(any("deleted: V49" in error for error in errors))

    def test_rejects_decision_for_wrong_version(self):
        temporary, base, candidate = self.roots()
        with temporary:
            (candidate / POLICY.MIGRATIONS / "V51__example.sql").write_text("SELECT 1;", encoding="utf-8")
            self.declare(candidate, "V52", "unknown")
            errors = POLICY.validate(base, candidate)
            self.assertTrue(any("no migrations.V51" in error for error in errors))

    def test_rejects_incomplete_decision(self):
        temporary, base, candidate = self.roots()
        with temporary:
            (candidate / POLICY.MIGRATIONS / "V51__example.sql").write_text("SELECT 1;", encoding="utf-8")
            self.declare(candidate, "V51", "unknown")
            errors = POLICY.validate(base, candidate)
            self.assertTrue(any("invalid classification" in error for error in errors))

    def test_rejects_predeclared_unchanged_decision(self):
        temporary, base, candidate = self.roots()
        with temporary:
            (candidate / POLICY.MIGRATIONS / "V51__example.sql").write_text("SELECT 1;", encoding="utf-8")
            self.declare(candidate, "V51")
            (base / POLICY.MANIFEST).write_bytes((candidate / POLICY.MANIFEST).read_bytes())
            errors = POLICY.validate(base, candidate)
            self.assertTrue(any("must add or update" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
