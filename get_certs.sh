#!/usr/bin/env bash
# Скачивает корневой и выпускающие сертификаты Минцифры в res/raw.
# Они будут "вшиты" в приложение и действуют только внутри него.
set -e
cd "$(dirname "$0")"
D=app/src/main/res/raw
mkdir -p "$D"
BASE=https://gu-st.ru/content/lending

get() {  # url name
  if curl -fsSL --retry 3 "$1" -o "$D/$2.tmp" && openssl x509 -in "$D/$2.tmp" -noout 2>/dev/null; then
    mv "$D/$2.tmp" "$D/$2.crt"; echo "OK: $2"
  else
    rm -f "$D/$2.tmp"; return 1
  fi
}

get "$BASE/russian_trusted_root_ca_pem.crt" russian_trusted_root_ca \
  || { echo "ОШИБКА: не удалось скачать корневой сертификат. Скачайте вручную с https://www.gosuslugi.ru/crt и положите как $D/russian_trusted_root_ca.crt (PEM)"; exit 1; }
get "$BASE/russian_trusted_sub_ca_pem.crt" russian_trusted_sub_ca || echo "предупреждение: sub CA не скачан (не критично)"
get "$BASE/russian_trusted_sub_ca_2024_pem.crt" russian_trusted_sub_ca_2024 || echo "предупреждение: sub CA 2024 не скачан (не критично)"

echo "--- Отпечаток корневого (сверьте с gosuslugi.ru/crt):"
openssl x509 -in "$D/russian_trusted_root_ca.crt" -noout -subject -enddate -fingerprint -sha256
