# MP4 : remesure du palier 22

Même fichier utilisateur, mêmes modèles, nouvelles archives construites depuis les octets originaux et une nouvelle trace FFmpeg. Aucune ancienne archive réutilisée par la mesure.

| Mesure | Octets |
| --- | ---: |
| MP4 original | 5 427 643 |
| Routeur automatique | 5 415 863 |
| Meilleur portefeuille NAL historique | 5 415 405 |
| Meilleurs en-têtes de slices | 5 414 825 |
| Meilleur modèle du corps vidéo | 5 414 803 |

Le résultat de version 20 est reproduit exactement. Gain supplémentaire : **22 octets (0,000406 %)** sur la meilleure référence. Gain total face au MP4 original : **12 840 octets (0,237 %)**. Ce gain supplémentaire est trop faible pour promouvoir le modèle expérimental.

Les modes 2, 3 et 4 donnent tous 5 414 803 octets. Les autres tailles sont 5 414 864 (mode 0), 5 414 959 (mode 1) et 5 415 737 (mode 5).

Les six candidats et quatre références sont restitués octet pour octet dans dix nouveaux processus CLI. SHA-256 de la source : `604f5646ea19d34261f0942f922399ecc90787fa244e719515e81103180998c8`. Les tests vidéo passent : 72 cas et 144 cas malformés.

Cette remesure concerne uniquement la vidéo WhatsApp H.264/AAC de 28,374785 secondes, 288 × 640 pixels. Elle vérifie la reproductibilité, sans apporter de nouvelle source indépendante ni de nouveau modèle. La préparation des limites de slices dépend de trace_headers FFmpeg ; la décompression Java est autonome. La trace historique reste dans mp4-slice-headers ; la nouvelle empreinte est conservée dans summary.json. Les grandes archives ne sont pas incluses dans le ZIP.

## Reproduction

Depuis le projet compilé (voir build-mp4-nal.sh) :

```bash
ffmpeg -v trace -i VID-20260930-WA0003.mp4 -map 0:v:0 -c copy -bsf:v trace_headers -f null - > trace.log 2>&1
java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.VideoBodyMeasure VID-20260930-WA0003.mp4 trace.log essais
java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.VideoBaselineMeasure VID-20260930-WA0003.mp4 essais
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress essais/body-m2.ivo restaure.mp4
cmp VID-20260930-WA0003.mp4 restaure.mp4
```

Tailles, empreintes, journaux et vérifications : research-results/2026-10-04/mp4-remeasure-v22. Sources sans .jar, .py ni .class dans le livrable.
