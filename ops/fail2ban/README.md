# fail2ban — alertes sur les logs d'auth de g8-api

4 jails qui lisent les events JSON de `AuthLogger` depuis le fichier
`/var/log/g8-api/auth.log` (écrit par Logback, voir
`src/main/resources/logback.xml`) et déclenchent ban iptables + email vers
`contact@the-gate.fr`.

> **Pourquoi un fichier et pas le journal systemd ?**
> Tenté en premier avec `backend = systemd` + `journalmatch =
> _SYSTEMD_UNIT=g8-api.service`. Sur ce serveur (Debian 12 + fail2ban 1.0.2),
> le reader systemd de fail2ban ne remontait aucun event en live alors qu'on
> en avait la preuve dans `journalctl` (et que `fail2ban-regex` matchait sur
> un dump fichier). `fail2ban-regex --journalmatch …` retournait
> `0 lines processed` — bug ou incompat silencieuse, jamais isolé.
> Bascule sur le pattern éprouvé (file + pyinotify, le même que sshd/postfix
> ici). Marche immédiatement.

| Jail | Event matché | Seuil | Bantime |
|---|---|---|---|
| `g8-refresh-reuse` | `auth.refresh_reuse_detected` | **1 / 1h** | 30 jours |
| `g8-magic-link-bf` | `magic_link.consume_failed` | 10 / 10 min | 24h |
| `g8-refresh-bf` | `auth.refresh` avec `success:false` | 20 / 10 min | 24h |
| `g8-magic-link-spam` | `magic_link.requested` avec `suppressed:true` | 5 / 1h | 24h |

## Pré-requis

- `fail2ban` ≥ 0.11 installé (déjà le cas sur YunoHost récent)
- MTA local capable d'envoyer (postfix YunoHost OK out of the box)
- **Le dossier `/var/log/g8-api/` doit exister et être writable par l'user qui
  exécute le JAR g8-api** (sinon Logback crash au boot). Voir étape *Installation*.

## Installation sur le serveur

### 1. Créer le dossier de log writable par g8-api

Le user qui exécute le JAR doit pouvoir écrire dans `/var/log/g8-api/`. Trouve-le
avec :

```bash
grep -E "^User=" /etc/systemd/system/g8-api.service /etc/systemd/system/g8-api.service.d/*.conf 2>/dev/null
ps -o user= -p "$(systemctl show -p MainPID --value g8-api)"
```

Puis (en root, remplace `<user>` par celui trouvé ci-dessus — typiquement
`yunohost_admin` ou `root` selon ta conf) :

```bash
sudo mkdir -p /var/log/g8-api
sudo chown <user>:<user> /var/log/g8-api
sudo chmod 755 /var/log/g8-api
```

### 2. Déployer le JAR mis à jour (logback.xml inclus)

```bash
# Local
./gradlew shadowJar
scp -P 2222 build/libs/g8-api.jar yunohost_admin@<host>:/tmp/

# Server
sudo systemctl stop g8-api
sudo mv /tmp/g8-api.jar /home/yunohost_admin/g8-api/g8-api.jar
sudo chown yunohost_admin:yunohost_admin /home/yunohost_admin/g8-api/g8-api.jar
sudo systemctl start g8-api

# Vérifier que le fichier de log a bien été créé par le boot
ls -la /var/log/g8-api/auth.log
# (vide ou avec quelques events si l'app a déjà tourné une seconde)
```

### 3. Copier les configs fail2ban

```bash
scp -P 2222 ops/fail2ban/filter.d/g8-*.conf  yunohost_admin@<host>:/tmp/
scp -P 2222 ops/fail2ban/jail.d/g8-api.conf  yunohost_admin@<host>:/tmp/

# Server (root)
sudo mv /tmp/g8-*.conf /etc/fail2ban/filter.d/  # 4 filtres
sudo mv /tmp/g8-api.conf /etc/fail2ban/jail.d/
sudo chown root:root /etc/fail2ban/filter.d/g8-*.conf /etc/fail2ban/jail.d/g8-api.conf
sudo chmod 644 /etc/fail2ban/filter.d/g8-*.conf /etc/fail2ban/jail.d/g8-api.conf

sudo fail2ban-client -t && sudo systemctl reload fail2ban && sudo fail2ban-client status
```

Tu dois voir les 4 jails `g8-*` dans la liste.

## Tester les regex à blanc (sans bannir)

`fail2ban-regex` lit le fichier de log directement, pas d'effet de bord :

```bash
sudo fail2ban-regex /var/log/g8-api/auth.log /etc/fail2ban/filter.d/g8-refresh-reuse.conf
sudo fail2ban-regex /var/log/g8-api/auth.log /etc/fail2ban/filter.d/g8-magic-link-bf.conf
sudo fail2ban-regex /var/log/g8-api/auth.log /etc/fail2ban/filter.d/g8-refresh-bf.conf
sudo fail2ban-regex /var/log/g8-api/auth.log /etc/fail2ban/filter.d/g8-magic-link-spam.conf
```

La sortie indique `Failregex: N total` pour chaque regex.

## Test end-to-end (forcer un ban depuis un faux event)

Pour vérifier que mail + ban se déclenchent réellement, le plus simple est
de générer un event qui matchera. Exemple pour `g8-refresh-bf` (le moins
critique) — depuis un autre poste, envoyer 25 refresh avec un token bidon :

```bash
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST https://api.the-gate.fr/v1/auth/refresh \
    -H "Content-Type: application/json" \
    -d '{"refreshToken":"bogus-token-for-fail2ban-test"}'
  sleep 1
done
```

Après ~20s tu dois :
- voir l'IP du poste émetteur bannie : `sudo fail2ban-client status g8-refresh-bf`
- recevoir un mail à `contact@the-gate.fr` avec l'IP + whois

Pour débannir manuellement :
```bash
sudo fail2ban-client set g8-refresh-bf unbanip <IP>
```

## Désactiver temporairement un jail (si trop bruyant)

```bash
sudo fail2ban-client stop g8-magic-link-spam
# ou éditer enabled = false dans /etc/fail2ban/jail.d/g8-api.conf puis reload
```

## Tweaks possibles

- **Changer le destinataire** : ouvrir `/etc/fail2ban/jail.d/g8-api.conf`,
  remplacer `dest=contact@the-gate.fr` partout (4 occurrences).
- **Ajouter une whitelist** (ton IP fixe pour éviter de t'auto-bannir en test) :
  dans `/etc/fail2ban/jail.local`, sous `[DEFAULT]` :
  ```
  ignoreip = 127.0.0.1/8 ::1 <ton-ip-fixe>
  ```
- **IPv6** : par défaut Debian 12 dispatche v4/v6 via `iptables-allports` +
  `ip6tables-allports`. Si un ban v6 ne s'applique pas, vérifier la présence
  de `iptables-allports-ipv6` dans `/etc/fail2ban/action.d/`.
