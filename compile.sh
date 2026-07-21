#!/bin/bash
# Script per scaricare le dipendenze, compilare e creare i file JAR

mkdir -p lib
if [ ! -f "lib/gson-2.10.1.jar" ]; then
    echo "Scaricamento di Gson 2.10.1 in corso..."
    wget -qO lib/gson-2.10.1.jar https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
    if [ $? -ne 0 ]; then
        echo "Errore nello scaricamento di Gson. Prova ad usare curl."
        curl -sS -L -o lib/gson-2.10.1.jar https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar
    fi
    echo "Gson scaricato o presente."
fi

echo "Compilazione del progetto..."
# Pulisce vecchi file e crea directory temporanea
rm -rf out
mkdir -p out

# Compila tutte le classi
# Trova tutti i file sorgente
find src/common src/server src/client -name "*.java" > sources.txt

if [ -s sources.txt ]; then
    javac -cp "lib/gson-2.10.1.jar" -d out @sources.txt
    
    # Estrai Gson in out per poterlo includere nel JAR facilmente, oppure possiamo impostare il Class-Path nel manifest
    # Usiamo l'approccio Manifest
    
    echo "Creazione Server.jar..."
    echo "Manifest-Version: 1.0" > server_manifest.txt
    echo "Main-Class: server.ServerMain" >> server_manifest.txt
    echo "Class-Path: lib/gson-2.10.1.jar" >> server_manifest.txt
    jar cvfm Server.jar server_manifest.txt -C out server -C out common
    
    echo "Creazione Client.jar..."
    echo "Manifest-Version: 1.0" > client_manifest.txt
    echo "Main-Class: client.ClientMain" >> client_manifest.txt
    echo "Class-Path: lib/gson-2.10.1.jar" >> client_manifest.txt
    jar cvfm Client.jar client_manifest.txt -C out client -C out common
    
    rm sources.txt server_manifest.txt client_manifest.txt
    echo "Compilazione completata. I file JAR sono pronti."
else
    echo "Nessun file sorgente trovato."
fi
