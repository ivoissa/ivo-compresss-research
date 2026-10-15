# Validation exécutable de DeflateIrEngine

Validation complémentaire réalisée avec un runtime OpenJDK 17 sur trois wrappers
contenant le même flux RFC1951 dynamique :

| Entrée | Taille originale | Taille `.ivo` | Moteur retenu | Identité après décompression |
|---|---:|---:|---|---|
| raw-DEFLATE | 738 octets | 244 octets | DEFLATE_IR | exacte |
| zlib | 744 octets | 244 octets | DEFLATE_IR | exacte |
| gzip | 756 octets | 254 octets | DEFLATE_IR | exacte |

Dans les trois cas, `deflate-check` a validé le parseur/réémetteur, le routeur a
sélectionné `DEFLATE_IR`, la décompression IVO a vérifié le SHA-256 et `cmp` a
confirmé l'identité octet pour octet.

Cette validation complète le corpus existant de 958 flux PDF réémis exactement.
