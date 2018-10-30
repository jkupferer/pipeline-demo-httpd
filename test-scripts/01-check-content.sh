if ! curl $SERVICE_URL | fgrep 'What hath God wrought?'
then
  echo "Did not get expected output."
  exit 1
fi
exit 0
