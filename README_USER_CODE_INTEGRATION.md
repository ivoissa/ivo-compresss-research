# Intégration consolidée et pistes — version 25

Les moteurs reçus peuvent maintenant participer à une comparaison du portefeuille habituel via une commande explicite. Le routeur conserve le plus petit conteneur IVO complet parmi les candidats qui restituent exactement le fichier. Une erreur de syntaxe ou un format incompatible rejette le candidat et laisse les autres moteurs en compétition. Le routeur par défaut reste inchangé ; le décompresseur habituel lit toutes ces archives.

```sh
bash build.sh
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli compress-enhanced original.mp4 output.ivo
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress output.ivo restored.mp4
cmp original.mp4 restored.mp4
```

La même commande accepte un MP3 ou un WebP. `bench-enhanced` affiche la comparaison sans écrire d'archive. En Java, `new Router(true)` active ce portefeuille ; `new Router()` conserve le portefeuille précédent. `UserCodecTrial` reste disponible pour un essai isolé avec contrôle exact avant écriture.

## Consolidation

- Profils arithmétiques figés et identifiants conservés : les archives de version 24 restent lisibles.
- Varints bornés, refus des champs tronqués, lecture complète des blocs et plafond d'inflation des métadonnées.
- Contrôles de tailles de boîtes, tables d'échantillons, offsets et longueurs NAL MP4 ; refus explicite des profondeurs vidéo autres que huit bits.
- Contrôles des chunks RIFF, dimensions et partitions VP8.
- Statistiques WebP par instance et suppression de la modification globale de System.out, y compris en cas d'erreur.
- Libération explicite des ressources natives des Deflater.
- Limites de ce portefeuille d'essai : entrée au plus 32 MiB, métadonnées et sortie au plus 128 MiB, au plus 16 384 macroblocs par image vidéo. Une entrée dépassant le plafond est laissée aux moteurs habituels. Cela ne permet pas encore de tester le MP3 Tao complet de plus de 32 MiB avec ce moteur.

Ces contrôles ne constituent pas un durcissement complet pour toutes les archives arbitraires. Le traitement en flux, les quotas par appel et l'exécution parallèle de gros fichiers restent à travailler ; les modèles consomment plusieurs centaines de Mo. Les autres restrictions de syntaxe de version 24 restent applicables.

## Vérifications

La construction générale et le nouveau test d'intégration passent. Neuf anciennes archives MP3/MP4/WebP sont restituées exactement. Trois comparaisons de portefeuille vérifient le choix de taille et la conservation du profil ; les cas invalides vérifient les métadonnées tronquées, les tailles excessives, le repli et la conservation de System.out après une erreur VP8.

Trois commandes `compress-enhanced` réelles, suivies d'une décompression dans un nouveau processus et d'une comparaison des octets, donnent :

| Source | Original | IVO complet | Observation |
|---|---:|---:|---|
| WhatsApp MP4 | 5 427 643 | 4 777 717 | Archive identique à version 24, 709 slices modélisées |
| Tao, extrait dix secondes MP3 | 357 362 | 320 146 | Archive identique à version 24 |
| kitten.webp | 51 064 | 47 848 | Payload identique ; nom original plus court dans le conteneur |

La dernière différence de taille par rapport aux 47 875 octets de version 24 vient uniquement du nom du fichier conservé dans le conteneur. Aucune nouvelle optimisation de compression n'est revendiquée dans cette version. Les meilleurs résultats de recherche antérieurs étaient 5 414 803 octets pour WhatsApp et 338 684 pour le même extrait MP3 ; le portefeuille automatique antérieur retenait respectivement 5 415 863 et 340 232 octets.

## Pistes classées à partir du code

| Priorité | Format et essai | Ce que le code apporte | Ce qui reste à mesurer |
|---|---|---|---|
| 1 | JPEG : mélange de contextes plus riche | Transposer les tables lentes/rapides, poids adaptatifs et calibration APM du CM aux prédictions causales de présence, catégorie et amplitude JPEG | Gain sur nos mélanges bayésiens actuels, taille complète, coût mémoire et temps |
| 2 | MP3 : différences des facteurs d'échelle | codeSf utilise déjà l'historique, mais code la valeur absolue par une suite de décisions unaires ; une différence bornée et pliée change la représentation | Gain face au profil MP32 figé, notamment sur musique et parole indépendantes |
| 3 | MP3 : blocs courts et mixtes | La correction d'échelle e4/sc est active seulement entre blocs longs ; bandOfs utilise des bandes longues | Contextes par bande courte et fenêtre, prenant en compte les subblock_gain, avec compatibilité des historiques |
| 4 | JPEG progressif : état connu avant chaque raffinement | Le lecteur exact préserve déjà les scans, décisions et bits de raffinement | Réutiliser causalement les coefficients déjà connus entre scans au lieu de dépendre seulement des cordes séparées |

Le JPEG possède déjà des mélanges adaptatifs (`BoundaryCategory`, `SpatialTailMixture`). La première piste enrichit ces mélanges ; elle ne suppose pas que notre JPEG manque de contexte. Le code WebP n'est pas un codec JPEG : on réutilise son prédicteur, en gardant le lecteur et le reconstructeur JPEG exacts.

Le MP3 possède déjà des contextes des granules précédents et de l'autre canal : refaire simplement les essais temporels précédents serait peu informatif. Un audit spécifique de l'extrait Tao montre que ses 724 granules non modélisés sont tous de longueur nulle ; les 952 granules non vides sont déjà acceptés. Il n'y a donc pas sur cet extrait de réserve importante de coefficients bruts à récupérer. Le test diagnostique de préfixes exacts avec 1 à 32 bits de traîne ne trouve aucun candidat supplémentaire. Le programme reproductible est conservé sous `research-experiments/user-code/src/io/ivo/usercode/Mp3CoverageAudit.java`.

Pour les blocs courts, il faut des sources musicales avec transitoires : le seul extrait de parole ne peut pas valider cette piste. Les changements de modèle ou de représentation demanderont un nouveau profil/format et un décodeur conservant les anciennes archives. Les pistes sont des hypothèses ; aucun pourcentage supplémentaire n'est annoncé.

Les mesures et journaux sont sous `research-results/2026-10-05/user-code-integration`.
