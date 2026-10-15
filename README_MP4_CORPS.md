# MP47 : régularités des corps de slices compressés — version 20

## Résultat

L’essai porte directement sur les octets EBSP des données vidéo compressées, après l’en-tête de slice. Sur WhatsApp, le meilleur candidat mesure **5 414 803 octets IVO**, contre **5 414 825** auparavant : **22 octets supplémentaires**, environ **0,000406 %** de l’ancienne archive. Les trois cas synthétiques conservent les moteurs précédents. Le gain est trop faible pour justifier une promotion automatique ou annoncer une amélioration importante du MP4.

| Source | Meilleur précédent IVO | Meilleur candidat MP47 | Décision |
| --- | ---: | ---: | --- |
| WhatsApp, fichier utilisateur | 5 414 825 | 5 414 803 | Gain de 22 octets |
| Détail synthétique | 9 793 | 9 794 | Ancien conservé |
| Uniforme synthétique | 1 071 | 1 103 | Ancien conservé |
| Multi-références synthétique | 18 754 | 18 755 | Ancien conservé |

La référence est le minimum des moteurs automatiques, des six profils NAL MP44 et des quatre variantes d’en-têtes MP46, sur les mêmes fichiers et avec le même nom/SHA-256. Les coûts des cordes vides, groupes, profils, champs et conteneur complet sont inclus. Pour le cas uniforme, la référence automatique de 1 071 est meilleure que la référence slices de 1 096 : le tableau utilise bien 1 071.

## Hypothèse

On cherche des régularités résiduelles au même emplacement dans les corps compressés des slices successives, sans modifier les champs du conteneur. Les en-têtes de slices gardent le XOR exact du meilleur profil MP46 ; les autres champs MP44 sont conservés. Les NAL non slices restent inchangés dans le contrôle et les variantes XOR : aucun gain sur SPS/PPS/SEI n’est attribué aux corps CABAC.

Les six variantes sont : contrôle brut, quatre cordes par position (0..63, 64..255, 256..1023, reste), puis XOR causal des premiers 64, 256, 1024 octets ou du corps entier avec le corps précédent du même type de slice. Quand le précédent est plus court, le suffixe reste brut. Les historiques sont reconstruits par le décodeur, sans transmettre le corps précédent. Les octets d’emulation prevention sont conservés exactement.

Les variantes XOR partielles 64/256/1024 ont la même taille gagnante sur WhatsApp. Le XOR intégral est plus gros : 5 415 737 octets. Les données compressées au-delà du préfixe n’exposent donc pas ici de répétitions exploitables par ce prédicteur simple.

Il s’agit d’une expérience sur le flux déjà compressé, **pas d’un décodage des coefficients ou symboles CABAC**. La préparation dépend encore de trace_headers FFmpeg pour les limites d’en-têtes ; le décodeur Java de MP47 est autonome. Le travail CABAC des versions précédentes reste conservé et n’est pas présenté comme un codec complet.

## Validation

Quatre vidéos × six variantes : **24 archives**, décompressées par 24 nouveaux processus du CLI et comparées aux sources. VideoBodyTest couvre 72 combinaisons de profils, longueurs variables, conservation EBSP, historique par groupe, non-transformation des NAL non slices, et 144 rejets malformés. Les tests MP44/MP45/MP46 passent.

MP47 utilise l’identifiant 27, enregistré pour lecture uniquement. Résultats et références : `research-results/2026-10-04/mp4-body`. Les grands candidats WhatsApp ne sont pas dans le ZIP ; leurs tailles, empreintes et validations sont conservées.

```sh
bash build-mp4-nal.sh
ffmpeg -v trace -i original.mp4 -map 0:v:0 -c copy -bsf:v trace_headers -f null - > trace.log 2>&1
java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.VideoBodyMeasure original.mp4 trace.log essais
java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.VideoBaselineMeasure original.mp4 essais
java -Xmx2g -cp target/classes io.ivo.cli.IvoCli decompress essais/body-m2.ivo restauree.mp4
```

La suite à plus fort potentiel reste l’IR exacte des résidus CABAC : coded_block_pattern, indicateurs de transformée, variation QP, puis coefficients et réémission exacte. Elle n’est pas réalisée par cette expérience et son gain reste inconnu. Référence normative : [ITU-T H.264, clause 9.3](https://www.itu.int/rec/T-REC-H.264/).
