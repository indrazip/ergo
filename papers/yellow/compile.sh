rm main.pdf
pdflatex -shell-escape main
bibtex -shell-escape main
pdflatex -shell-escape main
pdflatex -shell-escape main
rm *.aux
rm *.log
rm main.blg
rm main.bbl
rm main.out
rm main.toc
rm -r _minted-main/
