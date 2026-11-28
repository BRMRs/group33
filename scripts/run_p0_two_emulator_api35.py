#!/usr/bin/env python3
"""Run the existing offline P0 Debug chain on two job-owned API27 or API35 AVDs.

This is acceptance infrastructure for the Debug anonymous/memory-service path. It does not
claim Release authentication, Help delivery, cloud deployment or physical-device acceptance.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from urllib.request import urlopen

from run_debug_online_chain import phases_for


ROOT = Path(__file__).resolve().parents[1]
SERIALS = {"elder": "emulator-5560", "caregiver": "emulator-5562"}


def avd_names(api):
    if api not in {"27", "35"}:
        raise ValueError("P0 acceptance supports only API27 and API35")
    return {
        "elder": f"group33_p0_elder_api{api}",
        "caregiver": f"group33_p0_caregiver_api{api}",
    }


def system_image_package(api):
    if api == "27":
        return "system-images;android-27;default;x86_64"
    if api == "35":
        return "system-images;android-35;google_apis;x86_64"
    raise ValueError("P0 acceptance supports only API27 and API35")


def expected_phases():
    return phases_for(live_dose=True, dose_upload=True)


def verify_chain_results(rows):
    expected = expected_phases()
    actual = [(row.get("phase"), row.get("role")) for row in rows]
    if actual != expected:
        missing = [phase for phase, _ in expected if phase not in {item[0] for item in actual}]
        detail = ", ".join(missing) if missing else "phase order or role mismatch"
        raise ValueError(f"P0 chain result mismatch: {detail}")
    failed = [row["phase"] for row in rows if row.get("passed") is not True]
    if failed:
        raise ValueError(f"P0 chain failed phases: {', '.join(failed)}")
    return True


def finalize_result(
    *, head, expected_head, expected_api, devices, phases, server_healthy,
    private_state_removed
):
    result = {
        "head": head,
        "expected_head": expected_head,
        "devices": devices,
        "phases": phases,
        "server_healthy": server_healthy,
        "private_state_removed": private_state_removed,
        "passed": False,
        "failure": None,
    }
    if head != expected_head:
        result["failure"] = "head-mismatch"
        return result
    if not server_healthy:
        result["failure"] = "server-not-healthy"
        return result
    if set(devices) != {"elder", "caregiver"} or any(
        device.get("api") != expected_api
        or device.get("abi") != "x86_64"
        or device.get("stopped") is not True
        for device in devices.values()
    ):
        result["failure"] = "owned-avd-not-verified"
        return result
    try:
        verify_chain_results(phases)
    except ValueError as error:
        result["failure"] = str(error)
        return result
    if not private_state_removed:
        result["failure"] = "private-state-not-removed"
        return result
    result["passed"] = True
    return result


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--server-jar", type=Path, required=True)
    parser.add_argument("--locale", choices=("en-US", "zh-CN"), required=True)
    parser.add_argument("--api", choices=("27", "35"), required=True)
    args = parser.parse_args()
    owned_avd_names = avd_names(args.api)

    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("CI") != "true":
        raise RuntimeError("The destructive dual-AVD orchestrator is restricted to GitHub Actions")
    runner_temp = Path(os.environ["RUNNER_TEMP"]).resolve()
    output = args.output.resolve()
    if not output.is_relative_to(runner_temp):
        raise RuntimeError("Evidence output must stay inside the job temporary directory")
    output.mkdir(parents=True, exist_ok=True)
    server_jar = args.server_jar.resolve()
    if not server_jar.is_file() or not server_jar.is_relative_to(ROOT):
        raise RuntimeError("The Server JAR must be a built artifact in this exact checkout")

    head = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()
    expected_head = os.environ.get("EXPECTED_HEAD")
    if head != expected_head:
        raise RuntimeError("Checked-out head differs from the reviewed PR head")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    adb = sdk / "platform-tools/adb"
    emulator = sdk / "emulator/emulator"
    avdmanager = shutil.which("avdmanager") or str(
        sdk / "cmdline-tools/latest/bin/avdmanager"
    )
    app_apk = ROOT / "android-app/build/outputs/apk/debug/android-app-debug.apk"
    test_apk = ROOT / (
        "android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk"
    )
    proof_paths = [
        Path(__file__).resolve(),
        ROOT / "scripts/run_debug_online_chain.py",
        app_apk,
        test_apk,
        server_jar,
    ]
    if not all(path.is_file() for path in proof_paths):
        raise RuntimeError("Required exact-head build artifacts are missing")
    private_root = Path(tempfile.mkdtemp(prefix="group33-p0-private-", dir=runner_temp))
    avd_root = private_root / "avds"
    avd_root.mkdir()
    photo_root = private_root / "photos"
    photo_root.mkdir(mode=0o700)
    acceptance_clock = private_root / "server-clock"
    acceptance_clock.write_text("SYSTEM\n")
    acceptance_clock.chmod(0o600)
    env = dict(os.environ, ANDROID_AVD_HOME=str(avd_root))

    emulator_processes = {}
    emulator_logs = {}
    server_process = None
    server_log = None
    server_healthy = False
    phases = []
    devices = {
        role: {"serial": serial, "api": None, "abi": None, "stopped": False}
        for role, serial in SERIALS.items()
    }

    def run_adb_for_role(role, *command, timeout=30):
        return subprocess.run(
            [str(adb), "-s", SERIALS[role], *command],
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
        )

    try:
        connected = subprocess.check_output([str(adb), "devices"], text=True, env=env)
        if any(serial in connected for serial in SERIALS.values()):
            raise RuntimeError("Refusing to use an existing emulator on either selected port")

        server_log = (output / "server.log").open("w")
        server_env = dict(
            env,
            PHOTO_ENABLED="true",
            PLAN_ENABLED="true",
            OCCURRENCE_ENABLED="true",
            DOSE_GROUP_ENABLED="true",
            PERSISTENCE_MODE="memory",
            PHOTO_OBJECT_ROOT=str(photo_root),
            APP_ACCEPTANCE_CLOCK_FILE=str(acceptance_clock),
        )
        server_process = subprocess.Popen(
            [
                "java",
                "-jar",
                str(server_jar),
                "--server.port=18082",
                "--server.address=127.0.0.1",
            ],
            cwd=ROOT,
            env=server_env,
            stdout=server_log,
            stderr=subprocess.STDOUT,
        )

        for role in SERIALS:
            avd_path = avd_root / f"{role}.avd"
            created = subprocess.run(
                [
                    avdmanager,
                    "create",
                    "avd",
                    "--name",
                    owned_avd_names[role],
                    "--path",
                    str(avd_path),
                    "--package",
                    system_image_package(args.api),
                    "--device",
                    "pixel_6",
                ],
                input="no\n",
                capture_output=True,
                text=True,
                env=env,
                timeout=60,
            )
            (output / f"create-{role}-avd.log").write_text(
                created.stdout + created.stderr
            )
            created.check_returncode()
            emulator_logs[role] = (output / f"emulator-{role}.log").open("w")
            emulator_processes[role] = subprocess.Popen(
                [
                    str(emulator),
                    "-avd",
                    owned_avd_names[role],
                    "-port",
                    SERIALS[role].removeprefix("emulator-"),
                    "-no-window",
                    "-no-audio",
                    "-no-snapshot",
                    "-no-boot-anim",
                    "-gpu",
                    "swiftshader_indirect",
                    "-accel",
                    "on",
                    "-memory",
                    "1536",
                    "-cores",
                    "1",
                    "-camera-back",
                    "none",
                    "-camera-front",
                    "none",
                ],
                env=env,
                stdout=emulator_logs[role],
                stderr=subprocess.STDOUT,
            )

        deadline = time.monotonic() + 480
        while time.monotonic() < deadline:
            if server_process.poll() is not None:
                raise RuntimeError("The local Server exited before acceptance started")
            for role, process in emulator_processes.items():
                if process.poll() is not None:
                    raise RuntimeError(f"The {role} AVD exited before boot")
            if not server_healthy:
                try:
                    with urlopen("http://127.0.0.1:18082/health", timeout=1) as response:
                        server_healthy = response.status == 200
                except OSError:
                    pass
            booted = all(
                run_adb_for_role(role, "shell", "getprop", "sys.boot_completed", timeout=10)
                .stdout.strip()
                == "1"
                for role in SERIALS
            )
            if server_healthy and booted:
                break
            time.sleep(2)
        else:
            raise RuntimeError("Dual-AVD/Server readiness deadline exceeded")

        for role in SERIALS:
            devices[role]["api"] = run_adb_for_role(
                role, "shell", "getprop", "ro.build.version.sdk"
            ).stdout.strip()
            devices[role]["abi"] = run_adb_for_role(
                role, "shell", "getprop", "ro.product.cpu.abi"
            ).stdout.strip()
            name = run_adb_for_role(role, "emu", "avd", "name").stdout
            if owned_avd_names[role] not in name:
                raise RuntimeError(f"Wrong {role} AVD identity")
            if devices[role]["api"] != args.api or devices[role]["abi"] != "x86_64":
                raise RuntimeError(f"Wrong {role} API or ABI")

        chain_output = output / "chain"
        chain_env = dict(env, P0_ACCEPTANCE_CLOCK_FILE=str(acceptance_clock))
        run = subprocess.run(
            [
                "python3",
                str(ROOT / "scripts/run_debug_online_chain.py"),
                "--elder",
                SERIALS["elder"],
                "--caregiver",
                SERIALS["caregiver"],
                "--locale",
                args.locale,
                "--output",
                str(chain_output),
                "--offline-action-recovery",
                "--reset-test-apps",
            ],
            cwd=ROOT,
            env=chain_env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=1200,
        )
        (output / "chain-run.log").write_text(run.stdout)
        if run.returncode:
            raise RuntimeError("The two-emulator chain failed; inspect retained phase logs")
        phases = json.loads((chain_output / "results.json").read_text())
        verify_chain_results(phases)
    finally:
        for role, process in emulator_processes.items():
            if process.poll() is None:
                try:
                    name = run_adb_for_role(role, "emu", "avd", "name", timeout=10).stdout
                    if owned_avd_names[role] in name:
                        run_adb_for_role(role, "emu", "kill", timeout=10)
                    else:
                        process.terminate()
                    process.wait(timeout=30)
                except (OSError, subprocess.TimeoutExpired):
                    process.kill()
                    process.wait(timeout=10)
            devices[role]["stopped"] = process.poll() is not None
        for role in set(devices) - set(emulator_processes):
            devices[role]["stopped"] = True
        if server_process is not None and server_process.poll() is None:
            server_process.terminate()
            try:
                server_process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                server_process.kill()
                server_process.wait(timeout=10)
        for log in emulator_logs.values():
            log.close()
        if server_log is not None:
            server_log.close()
        if all(device["stopped"] for device in devices.values()):
            shutil.rmtree(private_root)
        private_state_removed = not private_root.exists()
        result = finalize_result(
            head=head,
            expected_head=expected_head,
            expected_api=args.api,
            devices=devices,
            phases=phases,
            server_healthy=server_healthy,
            private_state_removed=private_state_removed,
        )
        result.update(
            locale=args.locale,
            api=args.api,
            system_image=system_image_package(args.api),
            physical_device=False,
            production_provider=False,
            help_delivery=False,
            sha256={str(path.relative_to(ROOT)): sha256(path) for path in proof_paths},
        )
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result), flush=True)
    if not result["passed"]:
        raise RuntimeError(f"P0 Debug acceptance failed: {result['failure']}")


if __name__ == "__main__":
    main()
