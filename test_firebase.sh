#!/bin/bash
PROJECT="camdroid-e1a26"
REGIONS=("firebaseio.com" "asia-southeast1.firebasedatabase.app" "europe-west1.firebasedatabase.app")

echo "Starting Firebase Realtime Database Read/Write Test..."
for suffix in "${REGIONS[@]}"; do
  URL="https://${PROJECT}-default-rtdb.${suffix}"
  echo "Testing URL: ${URL}"
  
  # Attempt to write test string inside the permitted 'rooms' node path
  RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -d '"verification_success"' "${URL}/rooms/test_connection.json")
  
  if [ "$RESPONSE" == "200" ]; then
    echo "SUCCESS: Read/Write connection established!"
    echo "Your correct Database URL is: ${URL}"
    # Verify read
    VALUE=$(curl -s "${URL}/rooms/test_connection.json")
    echo "Confirmed read value: ${VALUE}"
    exit 0
  else
    echo "Failed (HTTP status: ${RESPONSE})"
  fi
done

echo "Error: Could not connect or write. Check that Realtime Database is enabled in the Firebase Console and Rules are set to public."
