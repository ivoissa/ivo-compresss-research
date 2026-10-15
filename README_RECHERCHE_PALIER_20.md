# Trois pistes mesurées — version 20, 4 octobre 2026

| Axe | Résultat mesuré | Décision |
| --- | --- | --- |
| JPEG progressifs et multiscans | 69 990 octets gagnés sur cinq fichiers externes, soit 3,385 % de leurs archives précédentes | Lecteur/réémetteur exact disponible ; élargir la validation avant intégration automatique |
| MP3 : contexte d’énergie | Tao : 559 765 octets gagnés, soit 0,676 % ; l3-test45 : 1 882 octets | Piste utile sur ces deux sources ; conserver les références sur les autres pertes |
| MP4 : corps vidéo compressés | WhatsApp : 22 octets gagnés ; aucun gain sur trois synthétiques | Pas de promotion ; poursuivre la syntaxe des résidus CABAC pour un potentiel supérieur |

**98 nouvelles archives** ont une restitution exacte dans des processus CLI séparés : 26 JPEG, 48 MP3, 24 MP4. Onze archives historiques compatibles sont également relues avec contrôle SHA-256. Compilation et tests généraux passent. Les paramètres, contrôles, limites et commandes sont documentés dans [README_JPEG_SCANS.md](README_JPEG_SCANS.md), [README_MP3_ENERGIE.md](README_MP3_ENERGIE.md) et [README_MP4_CORPS.md](README_MP4_CORPS.md).

Ces chiffres concernent les sources mesurées. Les JPEG dérivés ne sont pas des scènes indépendantes ; le gain MP3 ne démontre pas un gain moyen sur d’autres enregistrements. Le contrôle MP3 par position qui gagne 57 octets sur un sinus n’est pas attribué à l’énergie.

Les nouveaux formats JPS1/MP42/MP47 sont décompressés par le CLI standard, avec identifiants 25/26/27. Leur encodage reste explicite via les programmes de mesure. Le portefeuille automatique validé de version 19 est conservé. Les nouveaux `main` emploient des variables nommées initialisées depuis les arguments.

Les rapports complets, empreintes, références et petites archives expérimentales sont dans `research-results/2026-10-04/jpeg-scans`, `mp3-energy` et `mp4-body`. Les grandes archives sont omises pour garder un ZIP source transportable. Aucun `.jar`, `.py` ni `.class` dans le livrable.

Prochain palier conseillé : tester JPS1 sur un nouveau corpus progressif non utilisé pour ces mesures, et le contexte MP3 s16 sur plusieurs autres enregistrements de parole et de musique. Pour H.264, l’analyse de coded_block_pattern puis des coefficients reste le chantier profond ; le XOR des octets compressés n’a pas montré de gain substantiel.
