# MP4 V5 — payloads par type NAL

## Explication simple

L’expérience rassemble les payloads de NAL similaires dans des cordes séparées. Elle teste aussi un regroupement qui distingue un type répété du changement de type. Les en-têtes déjà conservés permettent de déduire la destination de chaque morceau au décodage : aucun pointeur individuel n’est transmis.

**Pas de gain sur la vidéo WhatsApp originale.** Le meilleur V5 mesure 5 415 434 octets, contre 5 415 405 pour V4 : il perd 29 octets. Le portefeuille doit conserver V4 sur ce fichier. Deux vidéos synthétiques améliorent la référence ; ni le fichier naturel ni son extrait ne progressent. La compression automatique reste désactivée pour cette expérience ; le décodeur est disponible dans le CLI principal.

| Cas | Origine | Ancien meilleur .ivo | Meilleur V5 .ivo | Décision |
|---|---|---:|---:|---|
| WhatsApp complet | Original utilisateur | 5 415 405 | 5 415 434 | Ancien conservé |
| Extrait fragmenté | Dérivé du même original | 388 526 | 388 537 | Ancien conservé |
| H.264 détaillé | Synthétique | 16 685 | 16 545 | Gain de 140 octets |
| HEVC détaillé | Synthétique | 14 985 | 15 002 | Ancien conservé |
| H.264 uniforme | Synthétique | 1 318 | 1 316 | Gain de 2 octets |

Quarante archives ont été relues par quarante nouveaux processus du CLI puis comparées octet pour octet aux sources. Les tailles incluent nom, SHA-256, profil, cordes et métadonnées. Ces cinq cas comprennent trois synthétiques, un seul original naturel et un dérivé : ce n’est pas une validation sur un grand corpus de vidéos.

## Partie technique

Le modèle part des archives MP44 de même source, avec le meilleur codage des longueurs disponible pour h=1 et h=2. Il réutilise exactement neuf champs codés et remplace uniquement la corde de payload vidéo. Le fichier source et l’archive MP44 servent à construire et mesurer le candidat ; ils ne sont pas requis pour décompresser MP45.

Quatre profils sont testés pour chacun des deux h :

- p=0 : t=b AND 31, partition correspondant au type AVC/H.264 ;
- p=1 : t=(b >>> 1) AND 63, partition correspondant au type HEVC ;
- p=2 : g=2*t+I(t=t_précédent), avec t AVC ;
- p=3 : même indicateur avec t HEVC.

b est le premier octet d’en-tête NAL, t_précédent=-1 au début. Les deux interprétations sont des modèles de partition réversibles : le programme ne déduit pas automatiquement le codec depuis avcC/hvcC et n’interprète pas la syntaxe des slices. Les types doivent être interprétés selon le codec réellement présent. Le h=2 AVC conserve aussi un octet qui dépasse son en-tête NAL ; cette séparation est un profil empirique, pas un parseur d’en-tête AVC de deux octets.

Seuls les groupes de payload non vides sont transmis : nombre de groupes, puis identifiant VarInt et corde codée encadrée. Le décodeur reconstruit les longueurs uint32, lit les en-têtes, recalcule g dans l’ordre original et consomme L_i-h octets du groupe correspondant. Tous les octets d’un groupe doivent être consommés. Les doublons d’identifiant, groupes absents, sous-flux, traînes, profils invalides et longueurs incohérentes sont rejetés.

Payload MP45 : signature, h, mode de longueur, p, puis les dix champs encadrés du modèle MP44. Le champ vidéo contient les groupes et leurs codeurs. Le conteneur IVO1 porte l’identifiant expérimental 21. Le décodeur restitue directement la corde vidéo brute au réémetteur MP44, sans la recompresser pendant la décompression. `Router` vérifie ensuite taille originale et SHA-256.

Les partitions ne transmettent pas de contexte appris au futur. La décision de groupe utilise seulement les en-têtes conservés et le type précédent. La séparation par type et les modèles contextuels sont des techniques connues ; l’expérience porte sur leur combinaison avec les cordes exactes et le coût final.

## Diagnostic du fichier WhatsApp

La corde de payload vidéo contient 5 064 386 octets bruts ; son codage V4 en contient 5 061 850. Les 709 unités AVC sont réparties en 706 de type 1 et 3 de type 5. Cette partition ne révèle pas assez de redondance supplémentaire pour compenser son coût sur ce fichier. Elle ne démontre pas que toute la redondance du codec a été exploitée.

La prochaine piste ambitieuse serait une représentation exacte de la syntaxe interne H.264, plutôt qu’un découpage supplémentaire des octets déjà codés. Elle nécessiterait un parseur et un réémetteur complets pour les profils ciblés, avec conservation des décisions entropiques et des bits de terminaison. Aucun gain de cette piste n’est mesuré ici.

## Tests et reproduction

Les tests ajoutés couvrent 24 cas combinant h, mode de longueur et partition, avec types répétés/changés et NAL à payload vide ; 72 entrées malformées sont rejetées. Les tests antérieurs du noyau, DEFLATE et MP3 ont été exécutés aussi. Les contrôles ont été lancés via les scripts Java 17 ; Maven n’a pas été exécuté dans cet environnement.

```bash
bash build-mp4-nal.sh
java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.NalMeasure original.mp4 bases-v4
# Remplacer les deux chemins ci-dessous par le meilleur candidat de chaque h sur cette source.
java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.NalTypeMeasure original.mp4 resultats-v5 bases-v4/h1-m2.ivo bases-v4/h2-m2.ivo
java -Xmx2g -jar target/ivo-0.8.0-research.jar decompress resultats-v5/h2-p0.ivo restaure.mp4
java -cp target/classes:target/nal-classes io.ivo.mp4.NalInspect bases-v4/h2-m2.ivo
```

Rapports complets : `research-results/2026-10-04/mp4-nal-types`, avec tailles de toutes les variantes, origines, empreintes et contrôles en nouveaux processus. Le temps du candidat à partir de MP44 ne représente pas le temps complet de compression depuis le fichier original.
