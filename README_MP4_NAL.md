# MP4 V4 — en-têtes NAL et longueurs

## Explication simple

Cette expérience réorganise les octets des unités vidéo sans modifier la vidéo ou le son. Elle compare six représentations : un ou deux octets d’en-tête séparés, avec longueurs originales, longueurs compactes, ou différences entre longueurs successives.

Le meilleur résultat sur la vidéo WhatsApp est 5 415 405 octets, contre 5 415 863 auparavant : **458 octets supplémentaires**, soit environ 0,0085 % de l’ancien conteneur. Le fichier original mesure 5 427 643 octets ; le gain total est environ 0,225 %.

Le progrès est faible. Le candidat reste expérimental, sans participation à la compression automatique par défaut. Le CLI sait décompresser les archives V4 de type 20 ; les anciens moteurs restent inchangés dans le concours de compression.

| Cas | Origine | Ancien meilleur .ivo | V4 meilleur .ivo | Gain supplémentaire retenu |
|---|---|---:|---:|---:|
| WhatsApp complet | Original utilisateur, H.264/AAC | 5 415 863 | 5 415 405 | 458 |
| Extrait WhatsApp fragmenté | Dérivé du même original | 388 552 | 388 526 | 26 |
| H.264 détaillé | Synthétique | 16 704 | 16 685 | 19 |
| HEVC détaillé | Synthétique | 15 000 | 14 985 | 15 |
| H.264 uniforme | Synthétique | 1 318 | 1 321 | 0 : ancien candidat conservé |

Les trente archives V4 ont été relues par trente nouveaux processus du CLI puis comparées directement aux sources. Quatre cas améliorent la référence ; un perd. Le corpus contient un seul original naturel, un dérivé et trois signaux synthétiques : il ne démontre pas un gain moyen sur les vidéos du marché.

## Partie technique

Les dix cordes V3 sont conservées : layout, en-têtes de boîtes, métadonnées, longueurs NAL, en-têtes NAL, payload vidéo, audio, autres échantillons, gaps, traîne. La syntaxe des échantillons reste celle du parseur V3, avec préfixes NAL de quatre octets. Les préfixes de 1/2/3 octets et les flux non compatibles ne sont pas pris en charge par cette expérience.

Le profil transmet h ∈ {1,2} et m ∈ {0,1,2}. Il s’agit d’une séparation testée au coût réel, pas d’un nouveau parseur qui identifie le codec et interprète ses slices. Un profil incompatible est écarté ; les autres peuvent rester éligibles.

Pour les longueurs L_i en uint32 :

- m=0 : octets big-endian originaux ;
- m=1 : nombre de NAL puis VarInt(L_i) ;
- m=2 : nombre de NAL puis VarInt(ZigZag(d_i)), avec d_i=L_i-L_(i-1), L_(-1)=0.

ZigZag(d)=(d<<1) XOR (d>>63), en long Java. Inverse : d=(z>>>1) XOR -(z AND 1), puis L_i=L_(i-1)+d. Chaque longueur restaurée doit appartenir à [0,2^32-1]. Le décodeur rejette les compteurs incohérents, traînes, profils invalides et dépassements. Les longueurs réémises retrouvent toujours leurs quatre octets originaux.

Payload : signature MP44, octet h, octet m, puis les dix cordes encadrées par VarInt de leur taille codée. Le conteneur IVO1 conserve taille originale, nom et SHA-256, avec identifiant expérimental 20. Les deux octets de profil et tous les encadrements sont inclus dans les mesures.

Le codeur des cordes conserve STORE, N&C, REF+N&C et zlib selon le coût réel. Les cordes identiques entre variantes réutilisent leur codage via un cache SHA-256 local au balayage ; toutes les variantes sont néanmoins réémises et comparées à l’original. Les temps avec ce cache ne sont pas des temps à froid par profil.

Tests : 18 cas de transformation des longueurs, valeurs extrêmes uint32 et différences négatives ; six rejets d’entrées malformées ; trente archives exactes dans de nouveaux processus ; tests antérieurs du noyau, DEFLATE et MP3 conservés. La validation Java 17 a été exécutée via les scripts ; Maven n’a pas été exécuté dans cet environnement.

## Reproduire

```bash
bash build-mp4-nal.sh
java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.NalMeasure original.mp4 resultats
java -Xmx2g -jar target/ivo-0.8.0-research.jar decompress resultats/h2-m2.ivo restaure.mp4
```

Comparer les six conteneurs à l’archive produite par le routeur normal, puis comparer les fichiers restaurés octet pour octet. Les mesures détaillées sont dans `research-results/2026-10-04/mp4-nal`.

## Prochaine piste

Le seul changement du framing apporte peu. Le prochain test devrait séparer les payloads selon le type NAL et mesurer des contextes conditionnés par les unités déjà décodées, avec le coût des cordes supplémentaires inclus. Le gain n’est pas démontré. Une interprétation exacte de la syntaxe interne des slices serait une étape distincte, plus complexe, avec conservation de tous les bits de codage et de remplissage.

Les VarInt, différences, ZigZag et codeurs utilisés sont connus. L’essai porte sur leur combinaison réversible, les cordes choisies et le coût final ; il ne constitue pas une nouveauté mathématique.
