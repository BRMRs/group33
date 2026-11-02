"""Private bounded metadata pipe. No filenames, paths, content or exceptions are printed."""
import os
import re
import stat
import sys
import uuid


def main():
    root = os.environ.pop('GROUP33_INVENTORY_ROOT')
    expected = (int(os.environ.pop('GROUP33_INVENTORY_DEVICE')), int(os.environ.pop('GROUP33_INVENTORY_INODE')))
    if os.stat not in os.supports_dir_fd or os.scandir not in os.supports_fd:
        raise RuntimeError('unsupported directory handles')
    fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        before = os.fstat(fd)
        if (before.st_dev, before.st_ino) != expected or stat.S_IMODE(before.st_mode) != 0o700:
            raise RuntimeError('invalid private directory')

        def stable():
            try:
                current = os.lstat(root)
                return (stat.S_ISDIR(current.st_mode) and stat.S_IMODE(current.st_mode) == 0o700
                        and (current.st_dev, current.st_ino, current.st_mtime_ns)
                        == (before.st_dev, before.st_ino, before.st_mtime_ns))
            except FileNotFoundError:
                return False

        def signature(name):
            value = os.stat(name, dir_fd=fd, follow_symlinks=False)
            return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns,
                    value.st_ctime_ns, value.st_mode, value.st_uid, value.st_gid)

        if not stable():
            raise RuntimeError('directory changed')
        observations = []
        names = set()
        with os.scandir(fd) as entries:
            print('READY', flush=True)
            for raw in sys.stdin:
                command = raw.rstrip('\n')
                if command == 'STABLE':
                    print('STABLE' if stable() else 'CHANGED', flush=True)
                elif command == 'NEXT':
                    if len(observations) >= 1001:
                        raise RuntimeError('entry budget')
                    entry = next(entries, None)
                    if entry is None:
                        print('END', flush=True)
                        continue
                    name = entry.name
                    if name in names:
                        raise RuntimeError('duplicate observation')
                    names.add(name)
                    value = signature(name)
                    observations.append((name, value))
                    mode = value[5]
                    private = stat.S_ISREG(mode) and stat.S_IMODE(mode) & ~0o600 == 0 and mode & stat.S_IRUSR
                    identity = None
                    if private and name.endswith('.blob'):
                        try:
                            parsed = uuid.UUID(name[:-5])
                            if str(parsed) == name[:-5]:
                                identity = str(parsed)
                        except ValueError:
                            pass
                    if identity is not None:
                        print('ENTRY\tOBJECT\t' + identity, flush=True)
                    elif private and re.fullmatch(r'\.upload-[a-zA-Z0-9_-]+\.part', name):
                        print('ENTRY\tTEMPORARY', flush=True)
                    else:
                        print('ENTRY\tUNSAFE', flush=True)
                elif command.startswith('CHECK\t'):
                    name, old = observations[int(command.split('\t')[1])]
                    try:
                        same = signature(name) == old
                    except FileNotFoundError:
                        same = False
                    print('STABLE' if same else 'CHANGED', flush=True)
                else:
                    raise RuntimeError('invalid command')
    finally:
        os.close(fd)


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('ERROR', flush=True)
        sys.exit(1)
