# Vitesse JPEG — 9 octobre 2026

Le signalement concernait `compress-fast`. Cette commande utilisait encore l’ancien portefeuille, contrairement à `compress`. Les commandes `compress`, `compress-fast` et `compress-compact` prennent désormais le chemin automatique JPEG. Les autres formats conservent leurs politiques antérieures ; cette mise à jour ne prétend pas accélérer tous les formats.

Optimisations sans changement du format ou des modèles : lecture et découpage JPEG partagés entre les profils 15/16 ; encodage unique des flux identiques (DC, profondeurs, catégories, signes et amplitudes) ; seuls les flux de présence sont encodés séparément ; hashes des 144 contextes de présence précalculés. Les candidats perdants ne sont plus décompressés inutilement dans ce portefeuille. Le routeur décompresse et compare encore chaque candidat retenu avant de l’accepter. Les API expérimentales `encodeNeighbourTrial` gardent leur contrôle interne. La préparation commune n’est pas un nouveau format de compression.

## Mesures

Photo 27667.jpg, 1536 × 1152, 597 558 octets. OpenJDK 17, même environnement Linux, `-Xmx512m`. Durées de compression avec vérification, sans la décompression supplémentaire faite par le banc après la mesure.

| Commande | Durée | Archive |
|---|---:|---:|
| Ancien compress-fast, une exécution isolée | 24,060 s | 505 582 octets |
| Ancien compress, trois exécutions | 8,545 / 7,492 / 7,742 s | 505 582 octets |
| Nouveau compress-fast, trois exécutions isolées | 4,285 / 3,630 / 3,699 s | 505 582 octets |

Gain d’environ 5,6 fois dès la première exécution face à l’ancien compress-fast. Les archives avant/après sont identiques octet par octet et décodées exactement par le routeur antérieur. Le nouveau compress avait aussi été mesuré à 4,149–4,494 s avant l’alignement de la commande FAST. Les temps varient avec la machine, le chauffage de la JVM, la résolution et le contenu. Ces mesures ne sont pas un engagement de durée sur Chromebook ou sur un JPEG original de 12/50 MP. Aucune limite de temps dure n’est ajoutée. Les JPEG progressifs gardent leur portefeuille dédié.

## Validation

`bash build.sh` passe entièrement, y compris les contrôles de compatibilité JPEG, MP3, MP4, de corruption, les archives historiques et les chemins AUTO/FAST. `PreferredPairTest` contrôle quatre fixtures et les huit profils indépendants 15/16 ; le résultat groupé doit correspondre au minimum exact, avec conservation du départage 15 en cas d’égalité. `AutoRouterTest` exige maintenant que FAST et AUTO produisent le même résultat réel et ne lancent que trois candidats. Le pom Maven inclut ces deux tests ; Maven n’est pas disponible dans l’environnement, les classes sont compilées et exécutées par build.sh.

## Utilisation

Avec IntelliJ : ouvrir le pom.xml à la racine, JDK 17+, classe io.ivo.cli.IvoCli, arguments :

```
compress-fast /chemin/photo.jpg /chemin/photo.ivo
```

Les modes `compress-standard` et `compress-enhanced` restent disponibles pour les comparaisons historiques/exhaustives et peuvent être beaucoup plus lents. Les mesures de performance sont faites sans points d’arrêt ni exécution pas à pas.
