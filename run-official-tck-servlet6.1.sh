#!/bin/bash
set -e

# ==============================================================================
# Script de lancement du TCK officiel Jakarta Servlet 6.1 pour Foy
# ==============================================================================
#
# Prérequis :
# 1. Avoir installé localement les artifacts du TCK officiel (non-publics) :
#    - jakarta.tck:servlet-tck-runtime:6.1.0
#    - jakarta.tck:servlet-tck-util:6.1.0
#    - jakarta.tck:servlet-tck:6.1.0 (pom)
#
# Utilisation :
#   ./run-official-tck-servlet6.1.sh                          # Lance un test de fumée
#   ./run-official-tck-servlet6.1.sh --all                    # Lance TOUTE la suite (long !)
#   ./run-official-tck-servlet6.1.sh -Dtest=ServletTests      # Lance toute une classe
# ==============================================================================

# S'assurer qu'on est à la racine du projet
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Filtrage de l'argument --all pour ne pas le passer à Maven
MVN_ARGS=()
USE_ALL=false
for arg in "$@"; do
    if [[ "$arg" == "--all" ]]; then
        USE_ALL=true
    else
        MVN_ARGS+=("$arg")
    fi
done

echo "🚀 [1/3] Installation du reactor Foy dans le dépôt local..."
mvn install -DskipTests

echo "📂 [2/3] Navigation vers foy-tck (POM Model 4.0.0 standalone hors reactor)..."
# foy-tck est in-reactor, activé par le profil Maven `tck`
# (harmonisation TCK, même pattern que les runners vidocq-runtime-tck-*).

# Préparation des arguments
# Si aucun test n'est spécifié et pas d'option --all, on lance un test simple
DEFAULT_TEST=""
if [[ "$*" != *"-Dtest="* && "$USE_ALL" == "false" ]]; then
    DEFAULT_TEST="-Dtest=servlet.tck.api.jakarta_servlet.servlet.ServletTests#DoDestroyedTest"
    echo "💡 Aucun test spécifié. Utilisation du test par défaut (smoke test) : $DEFAULT_TEST"
    echo "💡 Pour lancer l'intégralité du TCK officiel, utilisez : ./run-official-tck-servlet6.1.sh --all"
fi

echo "🧪 [3/3] Exécution de Maven avec le profil tck-official..."
mvn -P"tck,tck-official" -pl foy-tck test $DEFAULT_TEST "${MVN_ARGS[@]}"
