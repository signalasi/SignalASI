/* SPDX-License-Identifier: Apache-2.0 */
#define _GNU_SOURCE

#include <errno.h>
#include <grp.h>
#include <limits.h>
#include <sched.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/types.h>
#include <unistd.h>

#define WORKSPACE_PREFIX "/workspace/"
#define ISOLATED_WORKSPACE "/work"
#define ISOLATED_TMP ISOLATED_WORKSPACE "/.tmp"
#define EMPTY_MOUNT "/run/signalasi-empty"

struct launcher_options {
    const char *workspace;
    uid_t uid;
    gid_t gid;
    rlim_t cpu_seconds;
    rlim_t memory_bytes;
    rlim_t max_processes;
    rlim_t file_size_bytes;
    char **command;
};

static void fail(const char *message)
{
    fprintf(stderr, "signalasi-runtime-launcher: %s: %s\n", message, strerror(errno));
    exit(126);
}

static void invalid(const char *message)
{
    fprintf(stderr, "signalasi-runtime-launcher: %s\n", message);
    exit(126);
}

static unsigned long long parse_number(const char *value, unsigned long long minimum,
                                       unsigned long long maximum, const char *name)
{
    char *end = NULL;
    unsigned long long parsed;

    if (value == NULL || value[0] == '\0' || value[0] == '-')
        invalid(name);
    errno = 0;
    parsed = strtoull(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || parsed < minimum || parsed > maximum)
        invalid(name);
    return parsed;
}

static struct launcher_options parse_options(int argc, char **argv)
{
    struct launcher_options options = {0};
    bool has_uid = false;
    bool has_gid = false;
    bool has_cpu = false;
    bool has_memory = false;
    bool has_processes = false;
    bool has_file_size = false;
    int index = 1;

    while (index < argc) {
        const char *name = argv[index];
        const char *value;

        if (strcmp(name, "--") == 0) {
            options.command = &argv[index + 1];
            break;
        }
        if (index + 1 >= argc)
            invalid("missing option value");
        value = argv[index + 1];
        if (strcmp(name, "--workspace") == 0) {
            options.workspace = value;
        } else if (strcmp(name, "--uid") == 0) {
            options.uid = (uid_t)parse_number(value, 1, INT_MAX - 1, "invalid uid");
            has_uid = true;
        } else if (strcmp(name, "--gid") == 0) {
            options.gid = (gid_t)parse_number(value, 1, INT_MAX - 1, "invalid gid");
            has_gid = true;
        } else if (strcmp(name, "--cpu-seconds") == 0) {
            options.cpu_seconds = (rlim_t)parse_number(value, 1, 1801, "invalid CPU limit");
            has_cpu = true;
        } else if (strcmp(name, "--memory-bytes") == 0) {
            options.memory_bytes = (rlim_t)parse_number(
                value, 32ULL * 1024ULL * 1024ULL, 4ULL * 1024ULL * 1024ULL * 1024ULL,
                "invalid memory limit");
            has_memory = true;
        } else if (strcmp(name, "--max-processes") == 0) {
            options.max_processes = (rlim_t)parse_number(value, 1, 512, "invalid process limit");
            has_processes = true;
        } else if (strcmp(name, "--file-size-bytes") == 0) {
            options.file_size_bytes = (rlim_t)parse_number(
                value, 8ULL * 1024ULL * 1024ULL, 8ULL * 1024ULL * 1024ULL * 1024ULL,
                "invalid file size limit");
            has_file_size = true;
        } else {
            invalid("unknown option");
        }
        index += 2;
    }

    if (options.workspace == NULL || !has_uid || !has_gid || !has_cpu || !has_memory ||
        !has_processes || !has_file_size || options.command == NULL || options.command[0] == NULL)
        invalid("incomplete launch request");
    return options;
}

static void ensure_directory(const char *path, mode_t mode)
{
    struct stat metadata;

    if (mkdir(path, mode) != 0 && errno != EEXIST)
        fail("cannot create sandbox directory");
    if (lstat(path, &metadata) != 0)
        fail("cannot inspect sandbox directory");
    if (!S_ISDIR(metadata.st_mode) || S_ISLNK(metadata.st_mode)) {
        errno = EINVAL;
        fail("sandbox directory is unsafe");
    }
}

static void mask_directory(const char *target)
{
    if (access(target, F_OK) != 0)
        return;
    if (mount(EMPTY_MOUNT, target, NULL, MS_BIND | MS_REC, NULL) != 0)
        fail("cannot mask privileged runtime files");
}

static void mount_private_task_temp(const struct launcher_options *options)
{
    char mount_options[160];
    unsigned long long size_bytes = (unsigned long long)options->memory_bytes / 2;

    if (size_bytes < 16ULL * 1024ULL * 1024ULL)
        size_bytes = 16ULL * 1024ULL * 1024ULL;
    if (size_bytes > 256ULL * 1024ULL * 1024ULL)
        size_bytes = 256ULL * 1024ULL * 1024ULL;
    if (snprintf(mount_options, sizeof(mount_options),
                 "mode=0700,uid=%u,gid=%u,size=%llu,nr_inodes=16384",
                 (unsigned int)options->uid, (unsigned int)options->gid, size_bytes) >=
        (int)sizeof(mount_options)) {
        errno = EINVAL;
        fail("cannot configure private task temporary storage");
    }
    ensure_directory(ISOLATED_TMP, 0700);
    if (mount("tmpfs", ISOLATED_TMP, "tmpfs", MS_NOSUID | MS_NODEV, mount_options) != 0)
        fail("cannot mount private task temporary storage");
    if (chown(ISOLATED_TMP, options->uid, options->gid) != 0 || chmod(ISOLATED_TMP, 0700) != 0)
        fail("cannot secure private task temporary storage");
}

static void isolate_workspace(const struct launcher_options *options)
{
    char resolved[PATH_MAX];

    if (realpath(options->workspace, resolved) == NULL)
        fail("cannot resolve workspace");
    if (strncmp(resolved, WORKSPACE_PREFIX, strlen(WORKSPACE_PREFIX)) != 0)
        invalid("workspace is outside the shared root");
    if (unshare(CLONE_NEWNS | CLONE_NEWNET | CLONE_NEWIPC | CLONE_NEWUTS | CLONE_NEWPID) != 0)
        fail("cannot create task namespaces");
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0)
        fail("cannot isolate task mounts");

    ensure_directory(ISOLATED_WORKSPACE, 0700);
    ensure_directory(EMPTY_MOUNT, 0000);
    if (mount(resolved, ISOLATED_WORKSPACE, NULL, MS_BIND | MS_REC, NULL) != 0)
        fail("cannot bind task workspace");
    if (mount(NULL, ISOLATED_WORKSPACE, NULL,
              MS_BIND | MS_REMOUNT | MS_NOSUID | MS_NODEV, NULL) != 0)
        fail("cannot secure task workspace");
    mount_private_task_temp(options);

    mask_directory("/sys/firmware/qemu_fw_cfg");
    mask_directory("/dev/virtio-ports");
    if (umount2("/workspace", MNT_DETACH) != 0)
        fail("cannot hide peer workspaces");
    if (chdir(ISOLATED_WORKSPACE) != 0)
        fail("cannot enter task workspace");
}

static int wait_for_task(pid_t task)
{
    int status;

    while (waitpid(task, &status, 0) < 0) {
        if (errno == EINTR)
            continue;
        fail("cannot wait for sandbox task");
    }
    if (WIFEXITED(status))
        return WEXITSTATUS(status);
    if (WIFSIGNALED(status))
        return 128 + WTERMSIG(status);
    return 126;
}

static void enter_pid_namespace(void)
{
    if (prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0) != 0)
        fail("cannot bind task lifetime to launcher");
    if (mount("proc", "/proc", "proc", MS_NOSUID | MS_NODEV | MS_NOEXEC, NULL) != 0)
        fail("cannot mount private task process view");
}

static void apply_limit(int resource, rlim_t current, rlim_t maximum, const char *message)
{
    struct rlimit limit = {.rlim_cur = current, .rlim_max = maximum};
    if (setrlimit(resource, &limit) != 0)
        fail(message);
}

static void apply_security(const struct launcher_options *options)
{
    apply_limit(RLIMIT_CPU, options->cpu_seconds, options->cpu_seconds + 1, "cannot set CPU limit");
    apply_limit(RLIMIT_AS, options->memory_bytes, options->memory_bytes, "cannot set memory limit");
    apply_limit(RLIMIT_NPROC, options->max_processes, options->max_processes,
                "cannot set process limit");
    apply_limit(RLIMIT_FSIZE, options->file_size_bytes, options->file_size_bytes,
                "cannot set file size limit");
    apply_limit(RLIMIT_NOFILE, 256, 256, "cannot set file descriptor limit");
    apply_limit(RLIMIT_CORE, 0, 0, "cannot disable core dumps");

    if (prctl(PR_SET_DUMPABLE, 0, 0, 0, 0) != 0)
        fail("cannot disable task dumps");
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0)
        fail("cannot lock task privileges");
    if (setgroups(0, NULL) != 0)
        fail("cannot clear supplementary groups");
    if (setresgid(options->gid, options->gid, options->gid) != 0)
        fail("cannot drop task group privileges");
    if (setresuid(options->uid, options->uid, options->uid) != 0)
        fail("cannot drop task user privileges");
}

int main(int argc, char **argv)
{
    struct launcher_options options = parse_options(argc, argv);
    pid_t task;

    umask(0077);
    isolate_workspace(&options);
    task = fork();
    if (task < 0)
        fail("cannot create PID-isolated task");
    if (task > 0)
        return wait_for_task(task);

    enter_pid_namespace();
    apply_security(&options);
    unsetenv("LD_PRELOAD");
    unsetenv("LD_LIBRARY_PATH");
    unsetenv("PYTHONPATH");
    unsetenv("NODE_OPTIONS");
    setenv("HOME", ISOLATED_WORKSPACE, 1);
    setenv("TMPDIR", ISOLATED_WORKSPACE "/.tmp", 1);
    execvp(options.command[0], options.command);
    fail("cannot execute task command");
    return 126;
}
