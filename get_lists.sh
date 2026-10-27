#!/usr/bin/env bash
# Собирает список рекламных доменов из публичных фильтров (правила вида ||domain^) в assets/blocklist.txt.
# EasyPrivacy намеренно не используется: антифрод-скрипты банков часто попадают в списки приватности.
cd "$(dirname "$0")"
OUT=app/src/main/assets
mkdir -p "$OUT"
TMP=$(mktemp)
for u in \
  https://easylist.to/easylist/easylist.txt \
  https://easylist-downloads.adblockplus.org/advblock.txt \
  https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt ; do
  curl -fsSL --retry 3 "$u" >> "$TMP" 2>/dev/null && echo "OK $u" || echo "пропущен $u"
done
grep -hE '^\|\|[a-z0-9._-]+\^(\$(third-party|3p|all|important))?$' "$TMP" \
  | sed -E 's/^\|\|//; s/\^.*$//' | sort -u > "$OUT/blocklist.txt"
echo "доменов в списке: $(wc -l < "$OUT/blocklist.txt")"
rm -f "$TMP"
exit 0
