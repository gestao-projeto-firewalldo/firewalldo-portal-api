-- V1.8: atalhos do "Acesso rápido" com logo própria (imagem enviada pela equipe).
ALTER TABLE shortcuts ADD COLUMN image text NOT NULL DEFAULT '';
