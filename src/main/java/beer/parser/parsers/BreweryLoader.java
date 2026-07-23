package beer.parser.parsers;

import beer.parser.model.Brewery;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class BreweryLoader {

    public static List<Brewery> loadBreweries(String filePath) {
        List<Brewery> breweries = new ArrayList<>();

        try {
            if (!Files.exists(Paths.get(filePath))) {
                System.err.println("⚠️ [BreweryLoader] Файл не знайдено: " + filePath);
                return breweries;
            }

            List<String> lines = Files.readAllLines(Paths.get(filePath));

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                String[] parts = trimmed.split(",", 2);

                if (parts.length == 2) {
                    String name = parts[0].trim().replaceAll("^\"|\"$", "");
                    String url = parts[1].trim().replaceAll("^\"|\"$", "");

                    if (url.toLowerCase().contains("untappd.com")) {

                        if (url.endsWith("/")) {
                            url = url.substring(0, url.length() - 1);
                        }

                        if (!url.endsWith("/beer")) {
                            url = url + "/beer";
                        }

                        breweries.add(new Brewery(name, url));
                    }
                }
            }
            System.out.println(">>> [BreweryLoader] Успішно завантажено " + breweries.size() + " броварень із файлу: " + filePath);

        } catch (Exception e) {
            System.err.println("❌ [BreweryLoader] Помилка читання файлу броварень: " + e.getMessage());
        }

        return breweries;
    }
}