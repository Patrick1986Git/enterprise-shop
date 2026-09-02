import os
import subprocess
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY_ROOT / "scripts" / "transfer-prod-db-ownership.sh"


class TransferProductionDatabaseOwnershipTest(unittest.TestCase):

    def run_script(self, **overrides):
        environment = {
            "PATH": os.environ.get("PATH", ""),
            "DATABASE_ADMIN_URL": "postgresql://invalid/unavailable",
            "DATABASE_NAME": "enterprise_shop",
            "DATABASE_ADMIN_USER": "postgres",
            "LEGACY_RUNTIME_USER": "legacy_runtime",
            "FLYWAY_USER": "shop_migration",
            **overrides,
        }
        return subprocess.run(
            [str(SCRIPT)],
            cwd=REPOSITORY_ROOT,
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_rejects_unsafe_identifier_before_invoking_psql(self):
        result = self.run_script(LEGACY_RUNTIME_USER="legacy; DROP ROLE postgres")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("LEGACY_RUNTIME_USER must contain only letters, numbers, and underscores", result.stderr)

    def test_rejects_equal_runtime_and_migration_roles_before_invoking_psql(self):
        result = self.run_script(LEGACY_RUNTIME_USER="shop_runtime", FLYWAY_USER="shop_runtime")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("LEGACY_RUNTIME_USER and FLYWAY_USER must differ", result.stderr)


if __name__ == "__main__":
    unittest.main()
