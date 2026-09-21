import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
READINESS_HELPER = ROOT / "scripts/lib/postgres-readiness.sh"


class PostgresReadinessTest(unittest.TestCase):
    def test_restore_rehearsals_share_lifecycle_aware_readiness(self):
        for script_name in (
            "restore-rehearsal.sh",
            "historical-forward-restore-rehearsal.sh",
        ):
            script = (ROOT / "scripts" / script_name).read_text()
            self.assertIn("source scripts/lib/postgres-readiness.sh", script)
            self.assertIn(
                'wait_for_final_postgres "$container" "$ADMIN_USER"', script
            )
            self.assertNotIn("pg_isready", script)

    def test_transient_bootstrap_server_does_not_release_readiness_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary_directory = Path(directory)
            state_file = temporary_directory / "state"
            command_log = temporary_directory / "commands"
            state_file.write_text("0")
            docker = temporary_directory / "docker"
            docker.write_text(
                textwrap.dedent(
                    """\
                    #!/usr/bin/env bash
                    set -eu
                    state=$(cat "$FAKE_STATE")
                    printf '%s:%s\\n' "$state" "$*" >> "$FAKE_COMMAND_LOG"
                    case "$1:$2" in
                      inspect:--format) printf 'true\\n' ;;
                      exec:*)
                        if [[ "$*" == *"pg_isready"* ]]; then
                          [[ "$state" == 0 ]]
                        elif [[ "$*" == *"cat /proc/1/comm"* ]]; then
                          if [[ "$state" == 2 ]]; then printf 'postgres\\n'; else printf 'docker-entrypoint.sh\\n'; fi
                        elif [[ "$*" == *"SELECT current_user;"* && "$state" == 2 ]]; then
                          printf 'restore_admin\\n'
                        else
                          exit 1
                        fi
                        ;;
                      logs:*) printf 'bounded fake log\\n' ;;
                      *) exit 1 ;;
                    esac
                    """
                )
            )
            docker.chmod(0o755)
            sleep = temporary_directory / "sleep"
            sleep.write_text(
                "#!/usr/bin/env bash\n"
                "state=$(cat \"$FAKE_STATE\")\n"
                "if [[ $state -lt 2 ]]; then printf '%s' $((state + 1)) > \"$FAKE_STATE\"; fi\n"
            )
            sleep.chmod(0o755)

            environment = os.environ.copy()
            environment.update(
                {
                    "PATH": f"{temporary_directory}:{environment['PATH']}",
                    "FAKE_STATE": str(state_file),
                    "FAKE_COMMAND_LOG": str(command_log),
                }
            )
            command = textwrap.dedent(
                f"""\
                source {READINESS_HELPER}
                docker exec database pg_isready --username restore_admin --dbname postgres
                wait_for_final_postgres database restore_admin 5
                """
            )

            result = subprocess.run(
                ["bash", "-c", command],
                cwd=ROOT,
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(state_file.read_text(), "2")
            logged_commands = command_log.read_text()
            self.assertIn("0:exec database pg_isready", logged_commands)
            self.assertNotIn("0:exec database psql", logged_commands)
            self.assertNotIn("1:exec database psql", logged_commands)
            self.assertIn("2:exec database psql", logged_commands)


if __name__ == "__main__":
    unittest.main()
