import os
import shutil
from pathlib import Path
from datetime import datetime

ROOT = Path.cwd()

if not ((ROOT / "settings.gradle").exists()
         or (ROOT / "settings.gradle.kts").exists()):
    print("ERROR: Android project root not found!")
    print("Please cd into the correct project folder.")
    raise SystemExit(1)

BACKUP = ROOT.parent / (
    ROOT.name + "_backup_" +
    datetime.now().strftime("%Y%m%d_%H%M%S")
)

print("\n=== TCK FactoryFlow Cloud Migration ===")
print("Project:", ROOT)

print("\nCreating backup...")
shutil.copytree(
    ROOT,
    BACKUP,
    ignore=shutil.ignore_patterns(
        ".gradle", "build", ".git", ".idea"
    )
)
print("Backup created:", BACKUP)

kotlin_files = list(ROOT.rglob("*.kt"))
room_files = []
firebase_files = []
repository_files = []

for file in kotlin_files:
    try:
        text = file.read_text(encoding="utf-8")
    except Exception:
        continue

    if any(x in text for x in [
        "RoomDatabase", "@Dao", "@Entity",
        "Room.databaseBuilder"
    ]):
        room_files.append(file)

    if any(x in text for x in [
        "FirebaseFirestore", "FirebaseAuth",
        "FirebaseDatabase", "FirebaseFunctions"
    ]):
        firebase_files.append(file)

    if "Repository" in file.name:
        repository_files.append(file)

print("\n--- SCAN RESULTS ---")
print("Kotlin files:", len(kotlin_files))
print("Room files:", len(room_files))
print("Firebase files:", len(firebase_files))
print("Repository files:", len(repository_files))

report = ROOT / "CLOUD_MIGRATION_REPORT.txt"

with report.open("w", encoding="utf-8") as f:
    f.write("TCK FACTORYFLOW CLOUD MIGRATION REPORT\n\n")

    f.write("ROOM FILES:\n")
    for file in room_files:
        f.write(str(file.relative_to(ROOT)) + "\n")

    f.write("\nFIREBASE FILES:\n")
    for file in firebase_files:
        f.write(str(file.relative_to(ROOT)) + "\n")

    f.write("\nREPOSITORY FILES:\n")
    for file in repository_files:
        f.write(str(file.relative_to(ROOT)) + "\n")

print("\nReport created:", report)
print("\nBackup and scan completed successfully!")
print("No source files were modified.")
