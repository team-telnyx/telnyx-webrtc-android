#!/bin/bash

touch local.properties
echo "MOCK_USERNAME=\"$1\"" >> local.properties
echo "MOCK_PASSWORD=\"$2\"" >> local.properties
