# Mélangeur : stockage compact — version 29

Les estimateurs rapides de `Ivo.CM` peuvent être stockés sur 16 bits non signés (`char`) au lieu de 32 bits (`int`). Les tables de probabilités lentes, les poids, les calibrations, les contextes et les formules restent identiques. Cette option vise la mémoire ; les temps mesurés ne montrent pas une accélération systématique.

Le mode habituel garde les tableaux sur 32 bits. Le compactage est activé explicitement par `compress-compact`, `bench-compact`, `decompress-compact`, ou `new Router(Router.Mode.COMPACT)`. La compression compacte compare le même sous-ensemble de profils que `FAST`, avec les mêmes limites et son compromis éventuel de taille face à `ENHANCED`. Aucun nouveau format ou identifiant d'archive n'est introduit. Le lecteur ordinaire peut lire une archive produite en mode compact, et le lecteur compact une ancienne archive.

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli compress-compact original.mp3 output.ivo
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress-compact output.ivo restored.mp3
```

`compress-fast` et `decompress` gardent la représentation 32 bits. Pour les codecs ne faisant pas appel à `Ivo.CM`, l'option ne réduit pas leur mémoire. Le choix est limité à l'appel courant, avec restauration en cas d'exception et isolation entre threads. Il ne modifie aucun paramètre du modèle, et aucune propriété système n'est nécessaire.

## Équivalence

Tous les profils publiés emploient le taux `fr=5`, désormais explicite et constant. La mise à jour d'une valeur `f` pour un bit `y` est :

```
f' = f + ((65536*y - f) >> 5)
```

Pour `f` dans `[0,65535]` et `y` dans `{0,1}`, le résultat reste dans `[0,65535]`. Le calcul intermédiaire reste en `int` ; la conversion en `char` intervient seulement après la mise à jour. La lecture d'un `char` Java le convertit en entier non signé. Les probabilités sont donc identiques, et non une approximation à précision réduite.

Une copie du prédicteur 32 bits de version 28 est conservée dans `research-experiments/cm16/reference/Ivo-v28.java.txt`. `LegacyIvo` l'utilise comme oracle de test indépendant. Le test couvre exhaustivement les 131 072 mises à jour possibles et compare 800 000 prédictions et états internes, avec 8/10/12/14 experts, longues suites de zéros et de uns, collisions et contextes aléatoires, dans les deux modes de stockage. Les poids et les deux cartes APM finaux sont comparés également.

## Méthode de mesure

`DecoderCompare` charge l'ancien et le nouveau routeur dans deux classloaders isolés, sur la même JVM et les mêmes octets d'archive. Un échauffement par variante précède trois répétitions, dans un ordre alterné. La décompression inclut le conteneur et la vérification SHA-256. Les lectures et écritures de fichiers et le GC explicite précédent la phase sont hors du chronométrage ; les GC survenant pendant la phase sont inclus.

Le compteur d'allocation est celui du thread d'appel, obtenu via le MXBean OpenJDK. Il mesure les octets alloués sur le heap pendant l'opération, et non le maximum de mémoire simultanément utilisé, le RSS ou la mémoire native. Il complète la somme des pics des pools heap, dont les pics ne sont pas nécessairement simultanés. Les valeurs absolues dépendent de Java, de l'environnement et du GC.

Les essais exploratoires ont évalué le compactage seul, puis une fusion de boucles dans `predict`. Les deux étaient exacts. La fusion n'apporte pas de gain de temps fiable sur notre vidéo et n'est pas intégrée. Les sources et les mesures de ces tentatives sont conservées pour ne pas les confondre avec la variante retenue. Les fichiers `selected-*` mesurent la version optionnelle finale ; `selected-default-whatsapp` compare également le chemin 32 bits actuel à la version 28.

Les tables lentes restent sur 32 bits. Seule la moitié rapide est divisée par deux : les deux principaux ensembles de tables représentent ainsi 25 % de données en moins, sans prétendre réduire de 25 % toute la mémoire de l'application.

## Reproduire

```sh
bash benchmark-compact-mixer.sh archive.ivo original.mp4 resultats
```

Ce script compile les sources actuelles, restaure le prédicteur de version 28 dans un dossier de classes distinct et compare les décodeurs. Il ne produit pas de JAR. Les classes compilées et dossiers temporaires ne sont pas inclus dans le paquet livré. Les autres classes du routeur sont partagées : cela suffit à comparer les deux représentations du mélangeur sans changer les profils et le format d'archive. Les mesures initiales ont utilisé un instantané complet des classes de version 28.

## Résultats et validation

| Fichier | Temps 32 bits (ms) | Temps compact (ms) | Allocation 32 bits (MiB) | Allocation compacte (MiB) |
|---|---:|---:|---:|---:|
| business-synthetic | 24.24 | 18.16 | 4.14 | 4.14 |
| default-whatsapp | 22749.21 | 22713.11 | 328.55 | 328.55 |
| is-progressive_kitten-progressive | 329.30 | 300.30 | 22.02 | 20.18 |
| kitten-webp | 341.40 | 324.32 | 182.11 | 140.11 |
| tao-10s | 1073.16 | 1148.71 | 270.38 | 209.38 |
| user-22626 | 1255.25 | 1226.79 | 208.82 | 214.49 |
| whatsapp | 23531.25 | 23257.33 | 328.55 | 280.55 |

Médianes de trois répétitions après échauffement. La ligne `default-whatsapp` compare les deux chemins 32 bits : elle ne mesure pas le mode compact. Les données brutes, journaux et paramètres sont dans `research-results/2026-10-05/compact-mixer/`.

Sur ces entrées, les allocations diminuent de 61 MiB pour le MP3, 48 MiB pour le MP4 et 42 MiB pour le WebP. Le MP3 prend environ 7 % de temps supplémentaire. La petite différence de temps MP4 ne permet pas de conclure à une accélération fiable ; les essais exploratoires avaient montré un ralentissement. Le JPEG classique alloue ici davantage au total en mode compact malgré ses tables plus petites. Le JSON sert de contrôle sans mélangeur : sa différence de temps ne constitue pas un gain attribuable au compactage. Aucun gain de taille supplémentaire n’est attendu.

La suite complète de tests passe, y compris les neuf anciennes archives utilisateur, les 800 000 prédictions de référence, les mises à jour exhaustives et les contrôles de portée, exceptions et isolation entre threads. Les vérifications des nouvelles archives sont consignées dans `encoding-validation.json`.

17 nouvelles archives compactes sont strictement identiques aux archives FAST de version 28 (12 JPEG naturels, MP3, MP4, deux JSON et une fixture JPEG). Trois WebP supplémentaires produisent des archives identiques entre FAST actuel et COMPACT. Chacune des 20 archives est décodée dans un processus neuf par le lecteur version 28 et par le lecteur compact actuel : **40 restitutions exactes**. Les anciens fichiers sont aussi décodés par le mélangeur compact dans les mesures. Le script de reproduction a été exécuté avec succès sur le WebP.
