# MP4 — expérience sur les en-têtes de slices H.264

## Explication simple

La vidéo WhatsApp utilise le profil High et CABAC d’après la trace FFmpeg. Cette expérience isole les en-têtes de slices puis conserve les données CABAC telles quelles. Un deuxième profil remplace chaque en-tête par un XOR avec le précédent ; le décodeur fait l’opération inverse. Deux autres profils séparent les données selon le type de slice.

Le meilleur conteneur de la vidéo originale mesure **5 414 825 octets**, contre 5 415 405 auparavant : **580 octets supplémentaires**. Le gain total depuis les 5 427 643 octets originaux est environ **0,236 %**. Ce résultat expérimental est réellement mesuré, mais le moteur H.264 interne complet n’est pas réalisé.

| Cas | Origine | Ancien meilleur .ivo | Meilleur candidat slices .ivo | Décision |
|---|---|---:|---:|---|
| WhatsApp complet | Original utilisateur | 5 415 405 | 5 414 825 | Gain de 580 octets |
| Extrait fragmenté | Dérivé du même original | 388 526 | 388 507 | Gain de 19 octets |
| H.264 détaillé | Synthétique | 16 545 | 16 576 | Ancien conservé |
| H.264 uniforme | Synthétique | 1 316 | 1 335 | Ancien conservé |

Seize archives ont été décompressées par seize nouveaux processus du CLI et comparées directement aux sources. Les anciens candidats doivent rester disponibles ; cette expérience n’est pas activée dans la compression automatique.

**Limite essentielle :** la compression utilise une trace externe FFmpeg pour repérer les limites des en-têtes. Le décompresseur Java est autonome, mais le projet n’a pas encore son propre parseur complet de slices ni de décodeur/réémetteur des symboles CABAC. Aucun gain de cette étape plus profonde n’est annoncé.

## Partie technique

La base est un MP44 avec h=1. Tous les champs hors payload vidéo conservent leur codage exact. La trace contient pour chaque slice une position de fin d’en-tête RBSP, après alignement CABAC, et son slice_type. Le constructeur vérifie le nombre de slices rencontrées dans les NAL AVC de types 1/5. La trace CAVLC est refusée. Les formats HEVC et les NAL AVC étendus hors types 1/5 ne sont pas couverts comme slices par cet essai.

La position RBSP ne doit pas être appliquée directement aux octets échappés EBSP. La fonction `escapedPrefix` compte les octets de RBSP en sautant les octets d’emulation prevention 03 après deux 00, puis conserve tous les octets EBSP originaux du préfixe. Le corps original commence au premier octet non inclus. Aucune conversion des données CABAC, suppression permanente des octets 03 ou normalisation des bits d’alignement n’est effectuée.

Pour un en-tête octet H_i et le précédent H_(i-1), le résidu est R_i[j]=H_i[j] XOR H_(i-1)[j] si j est présent dans le précédent, sinon R_i[j]=H_i[j]. Reconstruction par le même XOR. Le précédent est remplacé par l’en-tête effectivement reconstruit ; les NAL non slices utilisent un préfixe vide et réinitialisent donc ce prédicteur. Aucun bit futur n’est utilisé.

Quatre modes sont encodés : 0 préfixes bruts/corps global ; 1 préfixes XOR/corps global ; 2 préfixes bruts/corps par slice_type modulo 5 ; 3 préfixes XOR/corps par type. Un sixième groupe accueille les NAL non slices. Les types sont transmis dans la métacorde pour les modes 2/3 : leur coût n’est pas caché.

Payload MP46 : signature, mode, dix champs encadrés du conteneur MP44. Le champ vidéo contient une métacorde (mode des longueurs, nombre de NAL, tailles de préfixes, groupes éventuels), les préfixes codés et un ou six corps codés. Le décodeur vérifie comptes, bornes, profils, consommation exacte de toutes les cordes et traînes. L’identifiant IVO expérimental est 22. Le routeur vérifie ensuite longueur originale et SHA-256.

Les 709 slices de la vidéo originale ont 22 064 bits d’en-tête selon la trace, en incluant leurs octets NAL : 2 758 octets, dont 709 octets NAL déjà conservés par MP44. Ce diagnostic porte sur les en-têtes, pas sur un potentiel mesuré des coefficients ou vecteurs de mouvement.

Tests ajoutés : 12 cas combinant trois modes de longueur et quatre profils de préfixe/corps, avec conservation EBSP, prédiction causale et 27 rejets d’entrées malformées. Les tests antérieurs du noyau, DEFLATE et MP3 passent également. Compilation Java 17 par les scripts ; Maven non exécuté ici.

## Reproduction

```bash
bash build-mp4-nal.sh
ffmpeg -v trace -i original.mp4 -map 0:v:0 -c copy -bsf:v trace_headers -f null - > trace.log 2>&1
# Construire les bases MP44 avec NalMeasure, puis choisir la plus petite base h=1.
java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.SliceMeasure original.mp4 bases-v4/h1-m2.ivo trace.log resultats-slices
java -Xmx2g -jar target/ivo-0.8.0-research.jar decompress resultats-slices/mode1.ivo restaure.mp4
```

La base MP44 et la trace servent uniquement à la construction du candidat, jamais à son décodage. Les traces, tailles des seize variantes, empreintes et validations sont dans `research-results/2026-10-04/mp4-slice-headers`.

Référence de l’outil d’analyse : FFmpeg coded bitstream/trace headers, https://ffmpeg.org/doxygen/trunk/cbs_8h.html. Le parseur FFmpeg guide cette expérience ; il ne doit pas être présenté comme un parseur H.264 écrit dans ce projet.

## Suite de recherche

Les réorganisations d’en-têtes apportent désormais des centaines d’octets sur le fichier naturel. Une piste distincte serait l’IR exacte des symboles CABAC puis des contextes temporels sur ces symboles ; elle exige l’analyse SPS/PPS, slices, états entropiques, dépendances spatiales et conservation des bits de terminaison. Ce travail reste à réaliser et sa rentabilité est inconnue. XOR, partitions, REF et codeurs utilisés sont connus : aucune nouvelle théorie mathématique n’est revendiquée.
