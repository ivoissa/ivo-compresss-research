# Taille, temps et mémoire — version 28

Cette étape ajoute une sélection rapide explicite, sans nouveau format d'archive. `compress` conserve son portefeuille précédent et `compress-enhanced` conserve tous ses essais. `compress-fast` choisit un sous-ensemble des candidats ; il peut donc produire une archive plus grande que le mode exhaustif sur des fichiers non couverts par les mesures. Chaque candidat retenu est toujours reconstruit exactement avant sélection. Le décompresseur ordinaire lit les trois modes.

## Sélection rapide figée

- JPEG classique : profils de frontière 9, 10, 15 et 16, contre huit profils dans `compress-enhanced`. Les autres moteurs du portefeuille restent comparés. Les profils de contrôle expérimentaux ne sont pas supprimés du décodeur.
- JPEG progressif et multiscan : profils 0 et 4, contre six profils dans `compress-enhanced`. Les anciens moteurs restent disponibles, notamment JPEG_IR sur les petites entrées.
- MP3 / MP4 de moins de 32 MiB et à signature admissible : STORE et moteur utilisateur figé sont essayés d'abord. Si le moteur spécialisé est exact et plus petit que STORE, le mode rapide le retient sans essayer le reste du portefeuille. S'il échoue ou ne réduit pas la taille, le portefeuille complet est essayé. Ce raccourci ne garantit pas le meilleur taux possible sur tous les fichiers.
- JSON, WebP, autres formats et médias hors limites : sélection inchangée, sous réserve du sous-ensemble JPEG décrit ci-dessus.

Les profils sont choisis à partir de nos mesures précédentes. Le corpus de cette étape réutilise ces fichiers : ce n'est pas une nouvelle validation indépendante de leur généralisation. Aucun ajustement de modèle ni apprentissage de paramètres sur les mesures de temps.

## Utiliser

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli compress-fast original.jpg rapide.ivo
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress rapide.ivo restitue.jpg
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli bench-fast original.jpg
```

En Java : `new Router(Router.Mode.FAST)`. Les constructeurs précédents gardent leur sens.

## Protocole

`PerformanceMeasure` compare `ENHANCED` et `FAST` dans une JVM isolée par fichier, sur les mêmes octets et avec le même nom conservé. Un échauffement complet par mode précède trois répétitions mesurées. L'ordre alterne entre modes. Les JVM sont lancées successivement, sans concurrence entre fichiers. Java 17, G1, `-Xms64m -Xmx2g`. Les fichiers sont lus avant les mesures ; les écritures sont après les régions chronométrées.

Le temps de compression comprend la sélection des moteurs, le codage, les contrôles de restitution de tous les candidats et la construction du conteneur. C'est le coût vérifié du routeur en mémoire, pas le débit pur du seul codeur gagnant. La décompression est mesurée séparément, avec lecture du conteneur et vérification SHA-256. Les débits emploient la taille originale, en MiB/s. Les fichiers et archives produits sont comparés octet par octet ; les répétitions doivent produire des archives identiques.

Un GC explicite a lieu avant chaque phase, hors chronométrage, pour limiter l'effet des candidats précédents. Les GC spontanés pendant une phase sont inclus dans son temps et recensés. Ce protocole contrôlé n'est pas une mesure de latence CLI de démarrage ou de service sous charge. Un seul échauffement et trois répétitions donnent un premier bilan, pas une caractérisation statistique complète.

La mesure mémoire par phase est la somme des pics d'usage des pools heap, remis à zéro avant chaque phase. Ces pics ne sont pas nécessairement simultanés : il s'agit d'une borne supérieure des pics des pools, incluant les données déjà conservées, et non d'un maximum exact du heap total ou d'un nombre d'octets alloués. La mémoire native n'y figure pas. Une seconde mesure échantillonne le RSS du processus toutes les 200 ms pour toute la JVM, échauffement et deux modes compris ; elle ne permet pas d'attribuer un RSS distinct à chacun des modes et peut manquer un pic court.

Le corpus contient six JPEG classiques, six progressifs, un extrait MP3 de parole, une vidéo MP4 utilisateur, un JSON synthétique répétitif et un petit rapport JSON réel du projet. Les deux JSON ne forment pas un corpus représentatif métier. Les sources, leurs empreintes, les valeurs brutes, les détails par moteur et l'environnement sont dans `research-results/2026-10-05/performance-fast`. La vidéo utilisateur reste à son emplacement de travail ; son chemin doit être remplacé pour une reproduction sur une autre machine.

## Reproduire une mesure

```sh
java -Xms64m -Xmx2g -XX:+UseG1GC -cp target/classes io.ivo.bench.PerformanceMeasure original.jpg resultats 1 3
```

Le programme produit `samples.csv`, `engine-details.txt`, `environment.txt`, `enhanced.ivo` et `fast.ivo`. `environment.txt` est écrit seulement après la réussite complète et sert de marqueur pour le rapport. Il refuse une restauration incorrecte ou une archive non déterministe. Le prélèvement RSS est externe au programme ; les temps et les mesures heap sont reproduits par cette seule commande.

Pour générer le tableau et le CSV récapitulatif de toutes les mesures terminées dans un répertoire :

```sh
java -cp target/classes io.ivo.bench.PerformanceReport research-results/2026-10-05/performance-fast
```

## Résultats

Les deux modes produisent des archives **identiques octet par octet sur les seize fichiers principaux**. Pour les groupes JPEG, les temps ci-dessous sont la somme des médianes par fichier, et non la médiane d’une seule opération sur le corpus. L’accélération est le rapport de ces sommes. Les temps de décompression sont comparables ; leurs écarts mesurés ne constituent pas une optimisation du décodeur.

| Format | Fichiers | IVO exhaustif / rapide (octets) | Compression exhaustive / rapide (s) | Accélération | Décompression exhaustive / rapide (s) |
|---|---:|---:|---:|---:|---:|
| JPEG-baseline | 6 | 1127853 / 1127853 | 71.841 / 50.966 | ×1.41 | 2.720 / 2.706 |
| JPEG-progressive | 6 | 391038 / 391038 | 35.598 / 14.233 | ×2.50 | 1.463 / 1.471 |
| MP3 | 1 | 320146 / 320146 | 15.342 / 2.236 | ×6.86 | 0.997 / 1.020 |
| MP4 | 1 | 4777717 / 4777717 | 117.112 / 45.953 | ×2.55 | 20.992 / 21.014 |
| JSON-synthetic | 1 | 6088 / 6088 | 0.070 / 0.074 | ×0.95 | 0.006 / 0.005 |
| JSON-report | 1 | 554 / 554 | 0.006 / 0.006 | ×1.01 | 0.001 / 0.001 |

Les JSON gardent le même portefeuille et la même archive ; leurs petites variations de temps ne sont pas un gain algorithmique. Le JSON synthétique passe de 96 019 à 6 088 octets (93,66 % de réduction), et le petit rapport de 862 à 554 octets (35,73 %). Ces deux fichiers ne permettent pas de classer tous les JSON face aux médias.

Un cas diagnostique montre le compromis : le JPEG de 3 075 octets produit **2 968 octets en exhaustif et 2 996 en rapide**, soit 28 octets de plus (+0,943 % sur l’archive exhaustive). Sa compression médiane passe de 0,284 à 0,192 seconde (×1,48). Le profil de catégorie 14 écarté est meilleur sur ce fichier. La restitution reste exacte dans les deux modes.

### Mémoire

Les valeurs suivantes sont le maximum de l’indicateur des pools heap sur les trois répétitions et les fichiers du groupe. Voir le protocole pour sa définition : ces sommes de pics sont des bornes supérieures, pas des pics simultanés exacts du heap total.

| Format | Indicateur heap compression exhaustif / rapide (MiB) | Indicateur heap décompression exhaustif / rapide (MiB) |
|---|---:|---:|
| JPEG-baseline | 1606.9 / 1414.2 | 123.0 / 109.9 |
| JPEG-progressive | 435.4 / 453.7 | 30.1 / 30.1 |
| MP3 | 760.4 / 375.9 | 334.1 / 334.3 |
| MP4 | 1041.4 / 385.4 | 325.2 / 324.7 |

L’indicateur baisse nettement pour la compression MP3 et MP4 lorsque les autres moteurs ne sont plus essayés. Il n’y a pas de baisse systématique pour les JPEG progressifs et la mémoire du décodeur reste comparable. La mémoire reste un axe d’optimisation.

La première collecte RSS ne confirmait pas l’identité de la JVM : ces valeurs sont exclues et remplacées par `null`. La collecte corrigée est conservée dans `rss-validated.json`, avec un indicateur de collecte partielle lorsque le début a été manqué. Les RSS valides portent sur toute la JVM, avec les deux modes ; ils ne servent pas à comparer la mémoire de chaque mode.

### Détail par fichier

| Source | IVO exhaustif / rapide (octets) | Compression exhaustive / rapide (s) | Accélération | Décompression exhaustive / rapide (s) |
|---|---:|---:|---:|---:|
| business-synthetic | 6088 / 6088 | 0.070 / 0.074 | ×0.95 | 0.006 / 0.005 |
| fixture-3-tradeoff | 2968 / 2996 | 0.284 / 0.192 | ×1.48 | 0.008 / 0.008 |
| holdout-24790 | 95471 / 95471 | 5.927 / 4.127 | ×1.44 | 0.250 / 0.232 |
| image-rs_jpg-progressive | 884 / 884 | 0.091 / 0.039 | ×2.32 | 0.001 / 0.000 |
| image-rs_progressive3 | 84911 / 84911 | 7.552 / 3.209 | ×2.35 | 0.330 / 0.347 |
| image-rs_tower_progressive | 59542 / 59542 | 5.310 / 2.206 | ×2.41 | 0.228 / 0.226 |
| is-progressive_kitten-progressive | 76496 / 76496 | 7.540 / 2.894 | ×2.61 | 0.313 / 0.325 |
| progressive-report | 554 / 554 | 0.006 / 0.006 | ×1.01 | 0.001 / 0.001 |
| pytorch_dog1 | 87444 / 87444 | 7.870 / 2.958 | ×2.66 | 0.307 / 0.297 |
| pytorch_dog2 | 81761 / 81761 | 7.237 / 2.927 | ×2.47 | 0.286 / 0.276 |
| tao-10s | 320146 / 320146 | 15.342 / 2.236 | ×6.86 | 0.997 / 1.020 |
| user-19414 | 160911 / 160911 | 11.141 / 7.883 | ×1.41 | 0.392 / 0.387 |
| user-22626 | 497727 / 497727 | 31.762 / 22.295 | ×1.42 | 1.150 / 1.138 |
| user-24792 | 114476 / 114476 | 7.015 / 5.084 | ×1.38 | 0.292 / 0.296 |
| user-25060 | 166708 / 166708 | 9.879 / 7.015 | ×1.41 | 0.390 / 0.386 |
| user-25066 | 92560 / 92560 | 6.117 / 4.561 | ×1.34 | 0.247 / 0.266 |
| whatsapp | 4777717 / 4777717 | 117.112 / 45.953 | ×2.55 | 20.992 / 21.014 |



## Prochaines pistes, non encore mesurées

1. Réduire les allocations et réutiliser l'analyse JPEG entre candidats : le mode rapide conserve encore le portefeuille JPEG historique, qui reste coûteux.
2. Examiner le stockage des estimateurs rapides de `Ivo.CM` sur 16 bits non signés. Avec le profil figé `fr=5`, leur mise à jour conserve une valeur dans cet intervalle ; les tableaux sont actuellement en `int`. Cela pourrait réduire la mémoire de ces tableaux sans changer les probabilités. Il faudra prouver l'identité des calculs et des archives, puis mesurer le temps et la mémoire ; aucun gain de débit n'est revendiqué ici.
3. Profiler les boucles de prédiction et de mise à jour du mélangeur avant une optimisation de la décompression. La sélection rapide ne réduit pas le travail du décodeur lorsque les archives sont identiques.
4. Valider la sélection rapide sur de nouveaux médias naturels, distincts des fichiers utilisés pour choisir les profils, et conserver le mode exhaustif pour ceux qui privilégient la taille.

## Validation finale

- 102 mesures chronométrées (51 paires), précédées de 34 exécutions d’échauffement.
- 34 fichiers d’archives décompressés dans de nouvelles JVM et comparés octet par octet aux sources ; SHA-256 vérifié par le CLI.
- Construction et suite générale réussies ; six scénarios du nouveau mode rapide couvrent la sélection préférée, le repli, les formats inchangés, le progressif et le rejet d’une corruption.
- Les neuf archives MP3/MP4/WebP antérieures du test d’intégration restent lisibles.
- Le rapport CSV produit en Java a été recoupé indépendamment avec les 102 observations brutes.
- Le paquet livré contient les sources Java et les résultats, sans `.jar`, `.class` ni `.py`.
