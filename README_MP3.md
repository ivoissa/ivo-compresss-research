# Reprise MP3 — comparaison réelle

## Explication simple

Le moteur existant MP31 produit un fichier .ivo de 87 774 500 octets sur le livre audio fourni, contre 95 634 889 octets d'origine. Le candidat MP32 qui conserve les données audio principales produit 90 916 311 octets : il est moins efficace. Les deux restitutions complètes ont été comparées octet pour octet avec l'original et son SHA-256.

Le moteur MP31 est conservé dans l'application. MP32 et MP33 sont des expériences séparées dans research-experiments/mp3-structural et nécessitent leur décodeur expérimental ; elles ne doivent pas être ouvertes comme MP31 par le CLI courant. Les données audio ne sont jamais transcodées.

## Partie technique

Le conteneur conserve la disposition des trames et les cordes prefix/header/CRC/side/main/trailing. Les coûts publiés incluent tous les champs .ivo, le nom et SHA-256. MP31 utilise ResearchCordCodec ; son meilleur mode pour main_data est zlib dans cet essai. Le candidat MP32 ne bat pas ce portefeuille. MP33 conserve les cordes MP31 et compare la corde side_info directe à sa version transposée, toutes deux codées par ResearchCordCodec. Le mode choisi est enregistré dans un octet. La transposition gagne : 3 323 338 contre 3 416 416 octets. Le .ivo complet MP33 mesure 87 681 423 octets, soit 93 077 octets de moins que MP31, avec reconstruction exacte.

REF : l'index associant un hash à des candidats conservait toutes les clés historiques. Même si une position était exclue de la recherche par distance, sa clé restait en mémoire. La correction supprime une clé lorsque sa position la plus récente est expirée. À chaque insertion p, la position p-maxDistance-1 est examinée ; la clé n'est retirée que si son candidat le plus récent est <= cette position. Aucune correspondance autorisée n'est retirée. Le format REF et son décodeur sont inchangés. L'index reste borné par le nombre de positions dans la fenêtre. Douze cas vérifient les mêmes octets encodés SHA-256 et les mêmes restitutions avant/après, pour données aléatoires, périodiques et mixtes, deux distances et deux nombres de candidats. SelfTest passe avec le nouvel index. La compression du fichier complet passe avec -Xmx2048m ; l'ancien index échouait à cette limite.

Audit MPEG-1 : pour chaque trame, l'intervalle de données est [8*(offsetMain-main_data_begin), start+sum(part2_3_length)). Les intervalles sont rapportés à la concaténation des main_data, et non à chaque trame physique isolément. L'union contient 693 447 633 bits, sans chevauchement ni dépassement observé. 397 289 octets sont entièrement hors de cette union, 196 198 octets sont partiellement utilisés. Il ne faut pas traiter naïvement la fin d'une trame comme données annexes : le réservoir change l'emplacement effectif des données audio. Aucun gain .ivo n'est déduit de cet audit.

Les scripts Java de comparaison sont des prototypes de recherche. Les gains restent à confirmer sur d'autres MP3. Lire README.md pour construire l'application Java 17. Les fichiers utilisateurs et les JAR ne sont pas redistribués.
