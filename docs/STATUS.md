# Statut des moteurs — Java 0.7.0 / recherche spécialisée 1.5

La règle est stricte : **"oui" signifie qu'un encodeur et un décodeur existent réellement dans ce projet et qu'un round-trip a été exécuté.**

| Moteur | Dans ce code | Round-trip | Routeur actif | Statut |
|---|---:|---:|---:|---|
| STORE | oui | oui | oui | prêt |
| N&C contexte | oui | oui | oui | prototype mesurable |
| REF + N&C | oui | oui | oui | prototype mesurable |
| JSON lexical | oui | oui | oui | prototype mesurable |
| **DEFLATE IR exact** | **oui** | **oui** | **oui** | base structurelle validée |
| Oracle JPEG exact | oui | oui | oui | infrastructure, pas innovation finale |
| N&C-DCT spatial/fréquentiel | en recherche | - | non | cible expérimentale actuelle |
| JSON structurel avancé | non | - | non | recherche à porter |
| CSV structurel | non | - | non | recherche à porter |
| PDF V2 structurel | oui | oui | oui, moteur 6 | profil GROUPED_RAW retenu |
| PDF V2 DEFLATE IR | oui | oui | option recherche | exact, moins compact sur le corpus |
| ZIP/DOCX V1 | oui | oui | oui, moteur 7 | compatibilité et repli |
| ZIP/DOCX V3 factorisé | oui | oui | oui, moteur 8 | profil par défaut si compatible |
| ZIP/DOCX V2 agrégé | oui | oui | option recherche | exact, rejeté sur le corpus |
| PNG structurel | non | - | non | prochaine intégration |
| JPEG V6.1 | oui | oui | oui, moteur 9 | payload réel exact |
| MP3 V1 | oui | oui | oui, moteur 10 | payload réel exact |
| MP4 V1 | oui | oui | oui, moteur 11 | repli boîtes ISO-BMFF |
| MP4 V2 | oui | oui | oui, moteur 12 | échantillons/pistes exacts |
| MP4 V3 | oui | oui | oui, moteur 13 | syntaxe NAL exacte |
| ZIP Bayes V1 | oui | oui | oui, moteur 14 | DOCX/XLSX/PPTX/ODF/EPUB/JAR/APK/ZIP, DEFLATE exact portable |

## Validation DEFLATE réelle effectuée pendant ce portage

Le parseur/réémetteur RFC1951 Java a été exécuté sur les flux `FlateDecode` extraits de trois PDF réels :

| Corpus | Flux zlib | Octets de flux | Réémission exacte |
|---|---:|---:|---:|
| Balzac | 27 | 87 738 | 27 / 27 |
| Java02 | 397 | 361 820 | 397 / 397 |
| CM1 Java | 534 | 2 164 156 | 534 / 534 |
| **Total** | **958** | **2 613 714** | **958 / 958** |

Ce test valide la **réversibilité du noyau RFC1951** sur ce corpus ; il ne prouve pas que `DEFLATE IR + N&C` est toujours plus petit que le flux DEFLATE d'origine.

L'agrégation a ensuite été testée dans les moteurs structurels. Sur le PDF de
32 186 791 octets, le profil `GROUPED_RAW` produit 28 497 446 octets, contre
28 704 096 pour l'agrégation DEFLATE IR. Sur le DOCX de 245 394 octets, V3
produit un conteneur réel de 242 431 octets, contre 242 627 pour V1 amélioré et
290 104 pour V2. Les 14 essais IR individuels sont tous perdants ; V3 conserve
donc les flux groupés bruts et factorise uniquement les champs ZIP redondants.

## Validation des moteurs médias runtime

| Cas réel | Original | IVO final | Gain | Moteur |
|---|---:|---:|---:|---|
| JPEG | 342 350 | 319 689 | 6,62 % | JPEG V6.1 / 9 |
| MP3 1 | 2 635 038 | 2 368 461 | 10,12 % | MP3 V1 / 10 |
| MP3 2 | 6 385 532 | 3 255 006 | 49,03 % | MP3 V1 / 10 |
| MP4 H.264/AAC | 4 244 346 | 4 196 431 | 1,13 % | MP4 V3 / 13 |

Sur le MP4, zstd-19 produit 4 191 360 octets : IVO reste 5 071 octets plus
grand. Sur le corpus synthétique, V3 gagne sur H.264 classique, H.264
fragmenté et HEVC ; MPEG-4 Part 2 retombe exactement sur V2.

## ZIP Bayes V1 — promu dans le runtime

La contribution du propriétaire du projet, produite avec Claude, est désormais
intégrée au routeur global sous l'identifiant stable 14. Le payload historique
imbriqué a été supprimé : le conteneur IVO global porte seul le nom, la taille
et le SHA-256. Sur `Sagessse bahaie.docx`, le conteneur global produit 144 646
octets à partir de 245 394, soit 41,06 % de réduction. Les 14 flux DEFLATE sont
reconstruits par description exacte des symboles.

Le chemin runtime est portable : il n'enregistre plus un niveau `Deflater`
comme recette de reconstruction. Si un flux RFC1951 ne peut pas être décrit et
réémis exactement, ses octets comprimés sont conservés. Le moteur est essayé
sur tout conteneur ZIP détecté, mais le routeur ne le sélectionne que si son
conteneur final exact est le plus petit.
