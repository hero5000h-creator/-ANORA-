# المصادر والتراخيص · Sources and licences

The ANORA app code is licensed under the GNU GPL v3 or later (see `LICENSE`). Each component below keeps its own licence and copyright.

| Component | Source | Licence |
|---|---|---|
| Madinah 1405H line layout and the "QMH Old Madina" font | [quran-madina-html](https://www.npmjs.com/package/quran-madina-html) by Ojuba | ["Waqf" General Public License 2.0](https://github.com/ojuba-org/waqf/tree/master/2.0) |
| Quran text (used to verify the layout text, ayah by ayah) | QuranEnc via [quran-json](https://www.npmjs.com/package/quran-json) | CC BY 4.0 (`licenses/CC-BY-4.0.txt`) |
| Prayer time calculation | [adhan-js](https://github.com/batoulapps/adhan-js) 4.4.6, © 2016 Batoul Apps (bundled as `src/adhan.js`) | MIT (`licenses/MIT.txt`) |
| Page, juz, hizb and surah tables | [quran-meta](https://www.npmjs.com/package/quran-meta), © 2020 Quran-Center | MIT (`licenses/MIT.txt`) |
| Word morphology (roots, lemmas, grammar) | Quranic Arabic Corpus via [quran-morphology](https://github.com/mustafa0x/quran-morphology) | GNU GPL |
| التفسير الميسّر | King Fahd Complex for the Printing of the Holy Qur'an | Used with permission |
| المختصر في التفسير | Markaz Tafsir (مركز تفسير للدراسات القرآنية) | Permission requested |
| تفسير السعدي، الجلالين، ابن كثير، البغوي، الميسّر في غريب القرآن | Digital text from [spa5k/tafsir_api](https://github.com/spa5k/tafsir_api) | Rights belong to their owners |
| Mutashabihat list (which ayat a hafiz tends to confuse), used for the coloured similar phrases | [Waqar144/Quran_Mutashabihat_Data](https://github.com/Waqar144/Quran_Mutashabihat_Data) by Waqar Ahmed, after the work of Qari Idrees al-Asim; converted by `tools/mutashabihat.py` into `data/mutashabihat.json`. The shared words are worked out in the app from the mushaf text. | CC0 1.0 (`licenses/CC0-1.0.txt`), credited at the author's request |
| Adhkar | «حصن المسلم», Sa'id ibn Ali ibn Wahf al-Qahtani | Takhrij reviewed |
| Recitation audio (Al-Husary, Mishary Alafasy, Al-Minshawi, Abdulbasit Abdussamad, Abu Bakr al-Shatri, Idrees Abkar, Ahmed Amer) and ayah timings | [MP3Quran.net](https://mp3quran.net) | Written permission from the MP3Quran team (support@mp3quran.com, 2026-11-03): use of the recitations and reciter list in ANORA for non-commercial, religious purposes, with attribution to MP3Quran.com inside the app. |
| Surah-name calligraphy on the page (the Madinah mushaf's titles) | King Fahd Complex for the Printing of the Holy Qur'an, as the `sura_names` font from [quran.com-frontend-next](https://github.com/quran/quran.com-frontend-next) (bundled as `src/suranames.css`) | Rights belong to the King Fahd Complex; used free of charge, non-commercially, under the Complex's general approval to ANORA. The frame around it is ANORA's own drawing. |
| UI fonts: Amiri, Amiri Quran, Scheherazade New, IBM Plex Sans Arabic | Google Fonts | SIL OFL 1.1 (`licenses/OFL-1.1.txt`) |

Open item: the Waqf GPL 2.0 and GPL-3.0 compatibility still has to be confirmed against the Waqf licence text.
