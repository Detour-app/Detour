# Detour on Proxmox VE, in one command

`detour-lxc.sh` stands up a Detour group server in a new LXC on a Proxmox VE host.
It is a thin wrapper over the kit in [`docker/prod/`](../prod/README.md), not a
second install path: it creates the container, installs Docker, checks out
`docker/prod/`, and runs the same two commands you would run by hand —
`./detour setup` and `docker compose up -d`. Everything after that is the kit.

## Run it

On the Proxmox host, as root:

```bash
bash -c "$(curl -fsSL https://raw.githubusercontent.com/Detour-app/Detour/main/docker/proxmox/detour-lxc.sh)" -- \
    --host detour.example.com --email you@example.com
```

Read a script before piping it to a root shell; this one is about 200 lines.

What it does:

1. Validates the arguments, so a typo creates nothing.
2. Downloads the newest Debian standard template if it is not cached.
3. Creates an unprivileged container (next free id, `local-lvm`, 2 cores,
   4 GiB RAM, 16 GiB disk, DHCP on `vmbr0`, `nesting=1,keyctl=1` so Docker runs,
   starts on boot).
4. Inside it: installs Docker from Docker's apt repository, sparse-checks out
   `docker/prod/` to `/opt/detour`, runs `./detour setup` with your options
   (generated secrets, the group layers, Caddy TLS) and `docker compose up -d`.
5. Prints the container's address, the Keycloak admin password `setup` generated
   (kept in `.env`), and the next steps.

If step 4 fails the container is left as it is, so you can look inside
(`pct enter <id>`); the script prints the `pct destroy` that removes it.

## Options

| Option | Default | |
| --- | --- | --- |
| `--host <domain>` | required | The address riders put in the app. |
| `--email <address>` | none | ACME contact for Caddy's certificates. |
| `--routing <region>` | off | Adds the routing layer for a region in [`regions.env`](../prod/config/regions.env). Needs `--memory`. |
| `--memory <MiB>` | `4096` | With `--routing`: GraphHopper's heap from the [Regions table](../prod/README.md#regions) plus ~4 GB, e.g. `14336` for `benelux`. |
| `--tag <tag>` | `latest` | API image tag. Pin a commit sha to be able to roll back. |
| `--ref <ref>` | `main` | Branch or tag of this repo to check out. |
| `--ctid <id>` | next free | Container id. |
| `--hostname <name>` | `detour` | Container hostname. |
| `--storage <storage>` | `local-lvm` | Root disk storage (`local-zfs` on a ZFS install). |
| `--template-storage <storage>` | `local` | Where LXC templates live. |
| `--bridge <bridge>` | `vmbr0` | Network bridge; the container takes a DHCP lease. |
| `--cores <n>` | `2` | CPU cores. |
| `--disk <GiB>` | `16` | Root disk. Add the region's extract and graph with `--routing`. |

## After it finishes

1. Point DNS for your hostname at your public address and forward ports 80 and
   443 to the container. Caddy fetches the certificate once both reach it. A
   DHCP reservation keeps the forward valid across reboots.
2. Invite riders from inside the container:

   ```bash
   pct enter <id>
   cd /opt/detour/docker/prod && ./detour invite
   ```

3. Upgrade the same way the kit does, from that directory:
   `docker compose pull && docker compose up -d`. `git -C /opt/detour pull`
   picks up new compose files first.

Backups, regions, first-boot times and the rest are in
[`docker/prod/README.md`](../prod/README.md).

## Testing

`detour-lxc.test.sh` runs the script with the Proxmox tools stubbed on `PATH`;
the in-container half runs locally against a copy of `docker/prod/`, so the
kit's real `./detour setup` writes a real `.env`:

```bash
bash docker/proxmox/detour-lxc.test.sh
```

This is not a [community-scripts](https://community-scripts.github.io/ProxmoxVE/)
submission; that project has its own layout and review, and offering it there is
a follow-up.
