# IVO Java 0.7.0 — ZIP Bayes V1

## Décision

Le candidat bayésien fourni par le propriétaire du projet et produit avec
Claude est promu dans le runtime sous l'identifiant stable `14`. Son code est
conservé avec son attribution dans `io.ivo.research.contrib`, tandis que
`ZipBayesianEngine` assure l'intégration au conteneur et au routeur IVO.

## Garanties

- sélection au coût réel du conteneur `.ivo` complet ;
- round-trip exécuté avant qu'un candidat puisse gagner ;
- taille originale et SHA-256 vérifiés lors de la restauration ;
- aucune dépendance runtime à la reproduction future de `Deflater(level)` ;
- conservation verbatim des flux ZIP/DEFLATE non analysables ;
- compatibilité de décodage des identifiants de moteurs 0 à 13.

## Mesures d'intégration

| Cas | Original | IVO 0.7 | Taille restante | Gain | Exact |
|---|---:|---:|---:|---:|---:|
| DOCX réel | 245 394 | 144 646 | 58,94 % | 41,06 % | oui |
| PPTX réel | 28 019 | 15 717 | 56,09 % | 43,91 % | oui |
| XLSX synthétique | 19 693 | 7 459 | 37,88 % | 62,12 % | oui |
| ODT synthétique | 1 579 | 597 | 37,81 % | 62,19 % | oui |
| ZIP mixte synthétique | 160 635 | 160 420 | 99,87 % | 0,13 % | oui |

Ces résultats ne constituent pas une promesse de ratio universel. ZIP Bayes
participe au concours du routeur ; `STORE` ou un autre moteur gagne lorsque sa
taille finale est meilleure.
