#!/bin/bash
while getopts u:p:t: flag
do
    case "${flag}" in
        u) user=${OPTARG};;
        k) key=${OPTARG};;
    esac
done
echo "User: $user";
echo "password: $key";

sed -i '' 's/<GPR_USR>/'"$user"'/g' ./github.properties
sed -i '' 's/<GPR_KEY>/'"$key"'/g' ./github.properties
