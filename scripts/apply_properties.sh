#!/bin/bash
while getopts u:p: flag
do
    case "${flag}" in
        u) user=${OPTARG};;
        p) pass=${OPTARG};;
    esac
done
echo "User: $user";
echo "password: $pass";

sed -i '' 's/<GPR_USR>/'"$user"'/g' ./local.properties
sed -i '' 's/<GPR_PASS>/'"$pass"'/g' ./local.properties
